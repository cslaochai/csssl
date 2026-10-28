package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.entity.CaCert;
import com.csssl.entity.CrlState;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CrlStateMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 吊销列表（CRL）：由服务签发二级CA（SUB_CA）私钥签名，判重发布。
 * <ul>
 *   <li>判重：对「吊销+挂起」序列集合排序后算 SHA-256 指纹，指纹未变则复用现有 crl_number
 *       与已发布 CRL（store-dir/crl/current.pem 直接回读），公开端点不再"每次 GET 自增 CRLNumber"。</li>
 *   <li>状态：ssl_crl_state 单行（id=1），发布入口 SELECT ... FOR UPDATE 行锁串行化，
 *       取代旧 store-dir/crl-state.json（首启把旧文件编号一次性迁移为初始 crl_number，保证单调连续）。</li>
 *   <li>全量 CRL：thisUpdate/nextUpdate + 单调递增 CRLNumber（RFC 5280 §5.2.3），30 天发布周期，
 *       nextUpdate 进入续期窗口时由每日定时任务主动重签（重签必须递增编号，否则依赖方按过期缓存拒收）。</li>
 *   <li>增量 CRL（dCRL）：仅含自最近一次全量发布以来新增的吊销/挂起条目，携带 DeltaCRLIndicator
 *       （RFC 5280 §5.2.4）指向基础全量编号；增量编号瞬态（基础+1），不推进全量状态行。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrlService {

    private final CaCertMapper certMapper;
    private final CrlStateMapper crlStateMapper;
    private final CaService caService;
    private final CssslProperties props;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** 全量 CRL 发布周期（天，行业 CRL 24h~7天发布为标配，此处沿用现有 30 天缓存窗口） */
    private static final int FULL_CRL_NEXT_UPDATE_DAYS = 30;
    /** 增量 CRL 发布周期（天）：增量比全量更频，依赖方按此缓存增量 */
    private static final int DELTA_CRL_NEXT_UPDATE_DAYS = 1;
    /** 全量 CRL 续期窗口（天）：nextUpdate 距当前不足此值时主动重签，避免依赖方拉到临界过期 CRL */
    private static final int REPUBLISH_AHEAD_DAYS = 7;

    /** 应纳入 CRL 的证书：已吊销（REVOKED）+ 挂起（FROZEN，reason=certificateHold）；排除 ROOT/SUB_CA（由上一级CA管理） */
    public List<CaCert> revokedList() {
        return certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .in(CaCert::getStatus, "REVOKED", "FROZEN")
                .notIn(CaCert::getCertType, CaService.TYPE_ROOT, CaService.TYPE_SUB_CA)
                .orderByDesc(CaCert::getRevokedAt));
    }

    /**
     * 判重发布入口（公开 GET、管理端下载与每日定时任务共用）：
     * <ol>
     *   <li>算当前「吊销+挂起」序列集合指纹；</li>
     *   <li>行锁读 ssl_crl_state：指纹未变且 nextUpdate 未入续期窗口 → 回读 current.pem 复用编号，不重签不递增；</li>
     *   <li>否则 crl_number+1、重签、原子覆盖 current.pem、更新状态行。</li>
     * </ol>
     */
    public synchronized CrlInfo publishIfNeeded() {
        List<CaCert> revoked = revokedList();
        String fingerprint = fingerprintOf(revoked);
        // 编程式事务：@Scheduled 与类内自调用不走注解代理，SELECT ... FOR UPDATE 的行锁必须显式事务包住
        // "读指纹→重签→回写"全程，否则语句结束即释放行锁、判重失效
        return transactionTemplate.execute(status -> doPublish(revoked, fingerprint));
    }

    /** 每日定时发布：主动续发/补发，把「吊销生效 → CRL 可见」窗口从"下次有人拉"收敛到至多下个调度点 */
    @Scheduled(cron = "${csssl.crl-refresh-cron:0 0 3 * * ?}")
    public void dailyRefresh() {
        try {
            CrlInfo info = publishIfNeeded();
            log.info("[CRL] 定时发布检查完成：条目数={}, CRLNumber={}, nextUpdate={}",
                    info.entries(), info.crlNumber(), info.nextUpdate());
        } catch (Exception e) {
            log.warn("[CRL] 定时发布失败（SUB_CA 离线或签发异常，待解封窗口处理）: {}", e.getMessage());
        }
    }

    /** 生成增量 CRL（dCRL）：自最近全量发布以来新增的吊销/挂起条目；无全量基础时返回空增量 */
    public synchronized DeltaCrlInfo buildDeltaCrl() {
        CrlState state = crlStateMapper.selectById(CrlState.SINGLE_ROW_ID);
        long baseNumber = state == null || state.getCrlNumber() == null ? 0L : state.getCrlNumber();
        if (state == null || baseNumber == 0 || state.getThisUpdate() == null) {
            log.info("[CRL] 增量生成跳过：尚无全量 CRL 基础（先生成全量）");
            return new DeltaCrlInfo("", null, null, 0, 0, 0);
        }
        LocalDateTime base = state.getThisUpdate();
        List<CaCert> added = revokedList().stream()
                .filter(c -> c.getRevokedAt() != null && c.getRevokedAt().isAfter(base))
                .toList();
        // 增量编号瞬态：基础全量编号+1；不写回状态行（全量编号只由 publishIfNeeded 推进）
        long number = baseNumber + 1;
        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        X509CRL crl = CertUtil.buildDeltaCrl(caCert, caKey, toEntries(added), number,
                baseNumber, DELTA_CRL_NEXT_UPDATE_DAYS);
        DeltaCrlInfo info = new DeltaCrlInfo(PemUtil.crlToPem(crl), toLocal(crl.getThisUpdate()),
                toLocal(crl.getNextUpdate()), added.size(), number, baseNumber);
        log.info("[CRL] 增量生成完成，新增条目数={}, CRLNumber={}, 基础全量={}", added.size(), number, baseNumber);
        return info;
    }

    /** 行锁事务内发布决策：判重复用 or 重签推进 */
    private CrlInfo doPublish(List<CaCert> revoked, String fingerprint) {
        CrlState state = ensureStateRowLocked();
        long current = state.getCrlNumber() == null ? 0L : state.getCrlNumber();
        boolean reusable = current > 0
                && fingerprint.equals(state.getFingerprint())
                && state.getNextUpdate() != null
                && state.getNextUpdate().isAfter(LocalDateTime.now().plusDays(REPUBLISH_AHEAD_DAYS));
        if (reusable) {
            CrlInfo reuse = readCurrentPem(state, revoked.size(), current);
            if (reuse != null) {
                return reuse;
            }
        }
        long number = current + 1;
        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        X509CRL crl = CertUtil.buildCrl(caCert, caKey, toEntries(revoked), number, FULL_CRL_NEXT_UPDATE_DAYS);
        String pem = PemUtil.crlToPem(crl);
        writeCurrentPem(pem);
        LocalDateTime thisUpdate = toLocal(crl.getThisUpdate());
        LocalDateTime nextUpdate = toLocal(crl.getNextUpdate());
        state.setCrlNumber(number);
        state.setFingerprint(fingerprint);
        state.setThisUpdate(thisUpdate);
        state.setNextUpdate(nextUpdate);
        state.setDeltaBaseNumber(number);
        crlStateMapper.updateById(state);
        log.info("[CRL] 全量重签发布：条目数={}, CRLNumber={}, thisUpdate={}, nextUpdate={}, 指纹={}",
                revoked.size(), number, thisUpdate, nextUpdate, fingerprint.substring(0, 8));
        return new CrlInfo(pem, thisUpdate, nextUpdate, revoked.size(),
                CertUtil.subjectCn(caCert), number, null);
    }

    /**
     * 获取单行状态（FOR UPDATE 行锁）；首启建行——若旧 store-dir/crl-state.json 存在，
     * 读入其编号作为初始 crl_number，保证编号跨改造单调连续（幂等，一次即可）。
     */
    private CrlState ensureStateRowLocked() {
        CrlState state = crlStateMapper.selectForUpdate();
        if (state != null) {
            return state;
        }
        state = new CrlState();
        state.setId(CrlState.SINGLE_ROW_ID);
        LegacyState legacy = readLegacyState();
        state.setCrlNumber(legacy.crlNumber());
        state.setThisUpdate(legacy.lastFullCrlTime());
        try {
            crlStateMapper.insert(state);
        } catch (DuplicateKeyException e) {
            // 并发首启：另一事务已建行；锁定读在 RR 隔离级别下仍读最新已提交版本，可直接重取
            state = crlStateMapper.selectForUpdate();
        }
        return state;
    }

    /** 旧 JSON 状态迁移（一次性）：读 crlNumber/lastFullCrlTime 字段；失败按初始状态处理 */
    private LegacyState readLegacyState() {
        Path f = Path.of(props.getStoreDir()).resolve("crl-state.json");
        if (!Files.exists(f)) {
            return new LegacyState(0L, null);
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(f, StandardCharsets.UTF_8));
            long number = node.path("crlNumber").asLong(0);
            String time = node.path("lastFullCrlTime").asText(null);
            LocalDateTime lastFull = time == null || time.isBlank() ? null : LocalDateTime.parse(time);
            log.info("[CRL] 已从旧 crl-state.json 迁移初始编号：crlNumber={}, lastFullCrlTime={}", number, lastFull);
            return new LegacyState(number, lastFull);
        } catch (Exception e) {
            log.warn("[CRL] 旧状态文件读取失败，按初始状态处理: {}", e.getMessage());
            return new LegacyState(0L, null);
        }
    }

    /** 判重指纹：序列集合（去重升序）SHA-256。只取序列不含原因码——原因变更随到期续发自然带出 */
    private static String fingerprintOf(List<CaCert> revoked) {
        String joined = revoked.stream()
                .map(c -> c.getSerialHex().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("CRL 指纹计算失败: " + e.getMessage(), e);
        }
    }

    /** 回读已发布 CRL（指纹未变即复用，不重签）；文件缺失/读失败返回 null 降级重签 */
    private CrlInfo readCurrentPem(CrlState state, int entries, long crlNumber) {
        try {
            Path f = currentPemFile();
            if (!Files.exists(f)) {
                return null;
            }
            return new CrlInfo(Files.readString(f, StandardCharsets.UTF_8),
                    state.getThisUpdate(), state.getNextUpdate(), entries,
                    CertUtil.subjectCn(caService.signingCert()), crlNumber, null);
        } catch (Exception e) {
            log.warn("[CRL] 已发布 CRL 回读失败，降级重签: {}", e.getMessage());
            return null;
        }
    }

    private Path currentPemFile() {
        return Path.of(props.getStoreDir(), "crl", "current.pem");
    }

    /** 已发布 CRL 落盘：临时文件 + 原子移动，杜绝并发下载端点读到半写 PEM */
    private void writeCurrentPem(String pem) {
        try {
            Path f = currentPemFile();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("current.pem.tmp");
            Files.writeString(tmp, pem, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("CRL 落盘失败: " + e.getMessage(), e);
        }
    }

    private List<CertUtil.CrlEntry> toEntries(List<CaCert> revoked) {
        List<CertUtil.CrlEntry> entries = new ArrayList<>();
        for (CaCert c : revoked) {
            Date revokedAt = c.getRevokedAt() == null ? new Date()
                    : Date.from(c.getRevokedAt().atZone(ZoneId.systemDefault()).toInstant());
            entries.add(new CertUtil.CrlEntry(c.getSerialHex(), revokedAt,
                    CertUtil.crlReasonCode(c.getRevokeReason())));
        }
        return entries;
    }

    private static LocalDateTime toLocal(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** 旧 crl-state.json 的迁移字段 */
    private record LegacyState(long crlNumber, LocalDateTime lastFullCrlTime) {
    }

    /** CRL 详情（PEM + 生效/下次更新时间 + 条目数 + 签发者 + CRLNumber + 增量基础编号） */
    public record CrlInfo(String pem, LocalDateTime thisUpdate, LocalDateTime nextUpdate,
                          int entries, String issuerCn, long crlNumber, Long deltaBase) {
    }

    /** 增量 CRL 详情（PEM + 更新时间 + 条目数 + 增量编号 + 基础全量编号） */
    public record DeltaCrlInfo(String pem, LocalDateTime thisUpdate, LocalDateTime nextUpdate,
                               int entries, long crlNumber, long baseCrlNumber) {
    }
}
