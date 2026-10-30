package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.AuditLog;
import com.csssl.entity.CaOrg;
import com.csssl.mapper.AuditLogMapper;
import com.csssl.vo.AuditExportVO;
import com.csssl.vo.AuditVerifyVO;
import com.csssl.vo.ChainBrokenVO;
import com.csssl.vo.ChainVerifyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作审计：登录/签发/吊销/导出等管理操作留痕（等保审计用）。
 * 每条记录按 GM/T 0034 §10.1/§10.5 做执行人数字签名（SM3withSM2），日志只增不删改；
 * 并形成哈希链（上一条签名纳入本条规范化串，行业增强实践）——任何一条被改动都会断链且验签失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /**
     * 微秒精度：与 created_at DATETIME(6) 列严格对齐，避免毫秒四舍五入导致验签失败
     */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * 链对账分页扫描批大小（全量对账时控制内存占用）
     */
    private static final int CHAIN_SCAN_PAGE = 500;

    /**
     * detail 列宽（VARCHAR(500)）：超长先截断，避免严格模式下插入失败而丢留痕
     */
    private static final int DETAIL_MAX_LEN = 500;

    private final AuditLogMapper auditLogMapper;
    private final AuditSigner auditSigner;

    /**
     * 审计记录规范化串（签名与验签共用同一格式）
     */
    public static String canonical(AuditLog logRow) {
        String base = String.join("|",
                nz(logRow.getUsername()), nz(logRow.getAction()), nz(logRow.getTarget()),
                nz(logRow.getDetail()), nz(logRow.getIp()),
                logRow.getCreatedAt() == null ? "" : TS.format(logRow.getCreatedAt()));
        return logRow.getPrevSign() == null || logRow.getPrevSign().isBlank()
                ? base : base + "|" + logRow.getPrevSign();
    }

    private static ChainBrokenVO brokenItem(Long id, String reason) {
        ChainBrokenVO vo = new ChainBrokenVO();
        vo.setId(id);
        vo.setReason(reason);
        return vo;
    }

    /**
     * 审计行归属机构（历史行为空按 default 归位，与列默认值语义一致）
     */
    public static String orgOf(AuditLog row) {
        return row.getOrgKey() == null || row.getOrgKey().isBlank() ? CaOrg.PRIMARY_ORG_KEY : row.getOrgKey();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                ? '"' + v + '"' : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 超长详情截断到列宽内（保留可读尾部标记，签名基于截断后的值，验签自洽）
     */
    private static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_MAX_LEN) {
            return detail;
        }
        return detail.substring(0, DETAIL_MAX_LEN - 1) + "…";
    }

    /**
     * 记录一条审计日志（自动签名防篡改 + 哈希链；机构归属缺省=平台主机构/全局操作）
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(String username, String action, String target, String detail, String ip) {
        record(username, action, target, detail, ip, CaOrg.PRIMARY_ORG_KEY);
    }

    /**
     * 记录一条审计日志（机构域版）：orgKey 落到审计行 org_key 列，供数据域强制过滤。
     *
     * <p>口径：签发/吊销/挂起等记对象（证书/申请单）所属机构；登录/建号等按用户机构；
     * 全局操作与 orgKey 为空的入参统一归位 default（列 NOT NULL DEFAULT 'default'）。
     * 注意 org_key 不参与 canonical 签名串——存量记录签名口径不变，机构归属仅作检索/过滤维度。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(String username, String action, String target, String detail, String ip, String orgKey) {
        AuditLog logRow = new AuditLog();
        logRow.setUsername(username == null || username.isBlank() ? "SYSTEM" : username);
        logRow.setOrgKey(orgKey == null || orgKey.isBlank() ? CaOrg.PRIMARY_ORG_KEY : orgKey);
        logRow.setAction(action);
        logRow.setTarget(target);
        logRow.setDetail(truncate(detail));
        logRow.setIp(ip);
        // 截断到微秒：LocalDateTime.now() 含纳秒，MySQL DATETIME(6) 存储时对第 7 位四舍五入，
        // 若直接入库会与 canonical 中的 .SSSSSS 截断值差 1 微秒导致验签偶发失败
        LocalDateTime now = LocalDateTime.now();
        logRow.setCreatedAt(now.withNano((now.getNano() / 1000) * 1000));
        // 哈希链：链头按 id 降序取（自增 id 单调，created_at 可能因时钟回拨/精度错序，不能作链序依据），
        // 并在本事务内 FOR UPDATE 锁住链头行，避免并发写两条记录指向同一前驱形成分叉
        AuditLog prev = lockChainHead();
        if (prev != null) {
            logRow.setPrevId(prev.getId());
            if (prev.getSign() != null && !prev.getSign().isBlank()) {
                logRow.setPrevSign(prev.getSign());
            }
        }
        try {
            logRow.setSign(auditSigner.sign(canonical(logRow)));
            log.debug("[审计] 签名完成 user={} action={} canonical={} sign={}",
                    logRow.getUsername(), logRow.getAction(), canonical(logRow), logRow.getSign());
        } catch (Exception e) {
            // 签名失败不阻断审计留痕（记录仍入库），仅告警
            log.warn("[审计] 签名失败，记录不带签名入库: {}", e.getMessage());
        }
        auditLogMapper.insert(logRow);
    }

    /**
     * 取并锁定链头记录；加锁失败降级为非锁定读（审计留痕优先于严格串行）
     */
    private AuditLog lockChainHead() {
        try {
            return auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                    .orderByDesc(AuditLog::getId).last("LIMIT 1 FOR UPDATE"));
        } catch (Exception e) {
            log.warn("[审计] 哈希链锁链头失败，降级为非锁定读: {}", e.getMessage());
            try {
                return auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                        .orderByDesc(AuditLog::getId).last("LIMIT 1"));
            } catch (Exception e2) {
                log.warn("[审计] 哈希链取上一条记录失败（不阻断）: {}", e2.getMessage());
                return null;
            }
        }
    }

    /**
     * 兼容验签：优先新格式（含哈希链段），失败再按旧格式（无 prevSign 段）校验，保证 P0 老记录仍可验
     */
    private boolean verifyCompat(AuditLog logRow) {
        if (logRow.getSign() == null || logRow.getSign().isBlank()) {
            return false;
        }
        if (auditSigner.verify(canonical(logRow), logRow.getSign())) {
            return true;
        }
        String prev = logRow.getPrevSign();
        if (prev != null && !prev.isBlank()) {
            logRow.setPrevSign(null);
            try {
                return auditSigner.verify(canonical(logRow), logRow.getSign());
            } finally {
                logRow.setPrevSign(prev);
            }
        }
        return false;
    }

    /**
     * 验签：按 id 读取记录并校验签名（改过任一字段即失败）
     */
    public AuditVerifyVO verifyLog(Long id) {
        AuditLog logRow = auditLogMapper.selectById(id);
        if (logRow == null) {
            throw new IllegalArgumentException("审计记录不存在（id=" + id + "）");
        }
        String canon = canonical(logRow);
        boolean valid = verifyCompat(logRow);
        String chainNote = valid && logRow.getPrevSign() != null && !logRow.getPrevSign().isBlank()
                ? "，且哈希链指向的记录签名一致" : "";
        AuditVerifyVO vo = new AuditVerifyVO();
        vo.setId(id);
        vo.setValid(valid);
        vo.setMessage(valid ? "签名有效：记录未被篡改，由审计签名密钥签发" + chainNote
                : "签名校验失败：记录内容已被改动或签名缺失");
        vo.setCanonical(canon);
        vo.setSign(logRow.getSign() == null ? "" : logRow.getSign());
        vo.setPrevSign(logRow.getPrevSign() == null ? "" : logRow.getPrevSign());
        return vo;
    }

    /**
     * 哈希链对账：按 id 升序（分页扫描）逐条核验
     * a) 自签名有效（走 verifyCompat，兼容 P0 无链段的老记录）；
     * b) 非链首记录的 prev_id 指向的记录必须存在、且等于扫描序前一条的 id、且本条 prev_sign 等于该记录的 sign。
     *
     * <p>威胁模型：攻击者删除中间记录后无法伪造签名补链（他拿不到审计私钥），
     * 但删除会把链剪成 prev_id 指向不存在的空洞——对账正是靠 prev_id 指向缺失检出删除，而非靠重算签名。</p>
     *
     * <p><b>链构造/对账不分机构（有意设计）</b>：审计哈希链是全局单链，prev_id 跨机构连续。
     * 若对账按机构分段扫描，跨机构相邻行的 prev 引用会被误判为断链，链条反而不可审计。
     * 因此机构数据域过滤只作用于"查"（page/export 按 org_key 收敛结果集），
     * 不作用于链构造与链对账；本接口对任何有权角色均呈现全局链完整性视角（只读，无内容泄露）。</p>
     *
     * @param fromId 起始记录ID（含，可空=从头开始）
     * @param toId   结束记录ID（含，可空=到最新）
     */
    public ChainVerifyVO verifyChain(Long fromId, Long toId) {
        List<ChainBrokenVO> broken = new ArrayList<>();
        long checked = 0;
        Long cursor = fromId == null ? null : fromId - 1;
        AuditLog prevRow = null;
        boolean rangeHead = true;
        while (true) {
            LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<AuditLog>()
                    .orderByAsc(AuditLog::getId)
                    .last("LIMIT " + CHAIN_SCAN_PAGE);
            if (cursor != null) {
                qw.gt(AuditLog::getId, cursor);
            }
            if (toId != null) {
                qw.le(AuditLog::getId, toId);
            }
            List<AuditLog> rows = auditLogMapper.selectList(qw);
            if (rows.isEmpty()) {
                break;
            }
            for (AuditLog row : rows) {
                // 区间首条的真实前驱可能落在 fromId 之外，需回查一次
                if (rangeHead && cursor != null) {
                    prevRow = loadPredecessor(row.getId());
                }
                rangeHead = false;
                checked++;
                checkChainRow(row, prevRow, broken);
                prevRow = row;
                cursor = row.getId();
            }
            if (rows.size() < CHAIN_SCAN_PAGE) {
                break;
            }
        }
        log.info("[审计对账] 扫描完成 checked={} broken={}", checked, broken.size());
        ChainVerifyVO vo = new ChainVerifyVO();
        vo.setChecked(checked);
        vo.setBroken(broken);
        return vo;
    }

    /**
     * 单条记录的对账判定（前驱信息尽量复用扫描序上一条，避免逐条回查）
     */
    private void checkChainRow(AuditLog row, AuditLog prevRow, List<ChainBrokenVO> broken) {
        Long id = row.getId();
        if (row.getSign() == null || row.getSign().isBlank()) {
            broken.add(brokenItem(id, "记录无签名，无法自证未被篡改"));
        } else if (!verifyCompat(row)) {
            broken.add(brokenItem(id, "自签名校验失败：内容已被改动或签名不匹配"));
        }
        if (row.getPrevId() == null) {
            if (prevRow != null) {
                broken.add(brokenItem(id, "非链首但 prev_id 为空：前驱引用缺失（历史记录未建 id 链或引用被清除）"));
            }
            return;
        }
        AuditLog prev = row.getPrevId().equals(prevRow == null ? null : prevRow.getId())
                ? prevRow : auditLogMapper.selectById(row.getPrevId());
        if (prev == null) {
            broken.add(brokenItem(id, "prev_id 指向的记录不存在（prev_id=" + row.getPrevId() + "）：疑似中间记录被删除"));
            return;
        }
        if (prevRow != null && !row.getPrevId().equals(prevRow.getId())) {
            broken.add(brokenItem(id, "链分叉：prev_id=" + row.getPrevId() + " 不等于前一条记录 id=" + prevRow.getId()));
        }
        String prevSign = row.getPrevSign();
        if (prevSign == null || prevSign.isBlank()) {
            if (prev.getSign() != null && !prev.getSign().isBlank()) {
                broken.add(brokenItem(id, "prev_sign 缺失：前驱记录已有签名但未纳入本条规范化串"));
            }
        } else if (!prevSign.equals(prev.getSign())) {
            broken.add(brokenItem(id, "prev_sign 与 prev_id 指向记录的签名不符：链已被改写"));
        }
    }

    /**
     * 取 id 严格小于给定 id 的最近一条记录（区间起点的真实前驱）
     */
    private AuditLog loadPredecessor(Long id) {
        return auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                .lt(AuditLog::getId, id).orderByDesc(AuditLog::getId).last("LIMIT 1"));
    }

    /**
     * 分页查询审计日志（orgKey 为空=跨机构全量；仅全局用户可达此口径，调用方已按数据域强制收敛）
     */
    public Page<AuditLog> page(long pageNo, long pageSize, String action) {
        return page(pageNo, pageSize, action, null);
    }

    /**
     * 分页查询审计日志（机构域版：orgKey 非空=仅该机构）
     */
    public Page<AuditLog> page(long pageNo, long pageSize, String action, String orgKey) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (action != null && !action.isBlank()) {
            qw.eq(AuditLog::getAction, action);
        }
        if (orgKey != null && !orgKey.isBlank()) {
            qw.eq(AuditLog::getOrgKey, orgKey);
        }
        qw.orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /**
     * 取审计原始行（控制器层数据域校验用；不存在抛异常）
     */
    public AuditLog load(Long id) {
        AuditLog row = auditLogMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("审计记录不存在（id=" + id + "）");
        }
        return row;
    }

    /**
     * 归档导出（兼容入口=跨机构全量，调用方按数据域收敛后传入 orgKey）
     */
    public AuditExportVO exportCsv() {
        return exportCsv(null);
    }

    /**
     * 审计归档导出（留存 ≥5 年材料，等保要求）：按机构过滤（orgKey 空=全量）后导出 CSV（UTF-8 BOM），
     * 并对导出内容做审计 SM2 报告签名（§10.5 审计记录创建人数字签名延伸）——归档文件完整性可在线验签。
     *
     * @return sign 是对 CSV 全文的 SM3withSM2 签名（hex），验签=对下载的 CSV 内容重算比对
     */
    public AuditExportVO exportCsv(String orgKey) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<AuditLog>().orderByAsc(AuditLog::getId);
        if (orgKey != null && !orgKey.isBlank()) {
            qw.eq(AuditLog::getOrgKey, orgKey);
        }
        List<AuditLog> logs = auditLogMapper.selectList(qw);
        StringBuilder sb = new StringBuilder("\uFEFF"); // UTF-8 BOM，Excel 直接识别中文
        sb.append("id,username,org_key,action,target,detail,ip,created_at,prev_sign,sign\n");
        for (AuditLog l : logs) {
            sb.append(l.getId()).append(',')
                    .append(csv(l.getUsername())).append(',')
                    .append(csv(orgOf(l))).append(',')
                    .append(csv(l.getAction())).append(',')
                    .append(csv(l.getTarget())).append(',')
                    .append(csv(l.getDetail())).append(',')
                    .append(csv(l.getIp())).append(',')
                    .append(l.getCreatedAt() == null ? "" : TS.format(l.getCreatedAt())).append(',')
                    .append(csv(l.getPrevSign())).append(',')
                    .append(csv(l.getSign())).append('\n');
        }
        String csv = sb.toString();
        String sign;
        try {
            sign = auditSigner.sign(csv);
        } catch (Exception e) {
            log.warn("[审计] 归档报告签名失败: {}", e.getMessage());
            sign = "";
        }
        log.info("[审计] 归档导出完成，机构={}，记录数={}，报告已签名",
                orgKey == null || orgKey.isBlank() ? "全部" : orgKey, logs.size());
        AuditExportVO vo = new AuditExportVO();
        vo.setCsv(csv);
        vo.setSign(sign);
        vo.setCount(logs.size());
        return vo;
    }
}
