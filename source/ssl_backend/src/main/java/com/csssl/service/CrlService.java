package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 吊销列表（CRL）：按需从已吊销证书记录实时生成，由服务签发二级CA（SUB_CA）私钥签名。
 * <ul>
 *   <li>全量 CRL：携带 thisUpdate/nextUpdate 与单调递增 CRLNumber（RFC 5280 §5.2.3），默认 30 天发布周期。</li>
 *   <li>增量 CRL（dCRL）：仅含自最近一次全量发布以来新增的吊销/挂起条目，携带 DeltaCRLIndicator
 *       （RFC 5280 §5.2.4）指向基础全量 CRL 编号，依赖方可全量+增量组合做吊销增量同步（对标格尔/厂商分块增量做法）。</li>
 *   <li>发布状态持久化：store-dir/crl-state.json（CRLNumber 计数 + 最近全量发布时间），重启不丢。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrlService {

    private final CaCertMapper certMapper;
    private final CaService caService;
    private final CssslProperties props;
    private final ObjectMapper objectMapper;

    /** 全量 CRL 发布周期（天，行业 CRL 24h~7天发布为标配，此处沿用现有 30 天缓存窗口） */
    private static final int FULL_CRL_NEXT_UPDATE_DAYS = 30;
    /** 增量 CRL 发布周期（天）：增量比全量更频，依赖方按此缓存增量 */
    private static final int DELTA_CRL_NEXT_UPDATE_DAYS = 1;

    /** 应纳入 CRL 的证书：已吊销（REVOKED）+ 挂起（FROZEN，reason=certificateHold）；排除 ROOT/SUB_CA（由上一级CA管理） */
    public List<CaCert> revokedList() {
        return certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .in(CaCert::getStatus, "REVOKED", "FROZEN")
                .notIn(CaCert::getCertType, CaService.TYPE_ROOT, CaService.TYPE_SUB_CA)
                .orderByDesc(CaCert::getRevokedAt));
    }

    /** 生成全量 CRL（X509 CRL v2，PEM 文本） */
    public String buildCrlPem() {
        return buildCrl().pem();
    }

    /** 生成全量 CRL 并附带 thisUpdate/nextUpdate（管理端展示与依赖方缓存用），发布后推进 CRLNumber 状态 */
    public CrlInfo buildCrl() {
        synchronized (this) {
            List<CaCert> revoked = revokedList();
            CrlState state = loadState();
            long number = state.crlNumber() + 1;
            X509Certificate caCert = caService.signingCert();
            PrivateKey caKey = caService.signingKey();
            java.security.cert.X509CRL crl = CertUtil.buildCrl(caCert, caKey, toEntries(revoked), number,
                    FULL_CRL_NEXT_UPDATE_DAYS);
            // 全量发布后推进编号并记录最近全量时间（供增量 CRL 计算基础）
            saveState(new CrlState(number, latestRevokedAt(revoked)));
            CrlInfo info = new CrlInfo(PemUtil.crlToPem(crl),
                    crl.getThisUpdate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    crl.getNextUpdate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    revoked.size(), CertUtil.subjectCn(caCert), number, null);
            log.info("[CRL] 全量生成完成，条目数={}, CRLNumber={}, thisUpdate={}, nextUpdate={}",
                    info.entries(), info.crlNumber(), info.thisUpdate(), info.nextUpdate());
            return info;
        }
    }

    /** 生成增量 CRL（dCRL）：自最近全量发布以来新增的吊销/挂起条目；无全量基础时返回空增量 */
    public DeltaCrlInfo buildDeltaCrl() {
        synchronized (this) {
            CrlState state = loadState();
            long baseNumber = state.crlNumber();
            if (state.lastFullCrlTime() == null) {
                log.info("[CRL] 增量生成跳过：尚无全量 CRL 基础（先生成全量）");
                return new DeltaCrlInfo("", null, null, 0, 0, 0);
            }
            LocalDateTime base = LocalDateTime.parse(state.lastFullCrlTime());
            List<CaCert> revoked = revokedList().stream()
                    .filter(c -> c.getRevokedAt() != null && c.getRevokedAt().isAfter(base))
                    .toList();
            long number = baseNumber + 1;
            X509Certificate caCert = caService.signingCert();
            PrivateKey caKey = caService.signingKey();
            java.security.cert.X509CRL crl = CertUtil.buildDeltaCrl(caCert, caKey, toEntries(revoked), number,
                    baseNumber, DELTA_CRL_NEXT_UPDATE_DAYS);
            saveState(new CrlState(number, state.lastFullCrlTime()));
            DeltaCrlInfo info = new DeltaCrlInfo(PemUtil.crlToPem(crl),
                    crl.getThisUpdate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    crl.getNextUpdate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    revoked.size(), number, baseNumber);
            log.info("[CRL] 增量生成完成，新增条目数={}, CRLNumber={}, 基础全量={}", revoked.size(), number, baseNumber);
            return info;
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

    /** 最近一条吊销/挂起时间（全量发布时记录为增量基准）；无条目时保持上次基准 */
    private String latestRevokedAt(List<CaCert> revoked) {
        if (revoked.isEmpty()) {
            CrlState state = loadState();
            return state.lastFullCrlTime();
        }
        LocalDateTime max = revoked.get(0).getRevokedAt(); // revokedList 已按时间倒序
        for (CaCert c : revoked) {
            if (c.getRevokedAt() != null && c.getRevokedAt().isAfter(max)) {
                max = c.getRevokedAt();
            }
        }
        return max == null ? null : max.toString();
    }

    private Path stateFile() {
        return Path.of(props.getStoreDir()).resolve("crl-state.json");
    }

    private CrlState loadState() {
        try {
            Path f = stateFile();
            if (Files.exists(f)) {
                return objectMapper.readValue(Files.readString(f, StandardCharsets.UTF_8), CrlState.class);
            }
        } catch (IOException e) {
            log.warn("[CRL] 状态文件读取失败，按初始状态处理: {}", e.getMessage());
        }
        return new CrlState(0, null);
    }

    private void saveState(CrlState state) {
        try {
            Path f = stateFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, objectMapper.writeValueAsString(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[CRL] 状态持久化失败: {}", e.getMessage());
        }
    }

    /** CRL 发布状态（持久化到 store-dir/crl-state.json） */
    public record CrlState(long crlNumber, String lastFullCrlTime) {
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
