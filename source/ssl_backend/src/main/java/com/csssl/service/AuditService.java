package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.entity.AuditLog;
import com.csssl.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 操作审计：登录/签发/吊销/导出等管理操作留痕（等保审计用）。
 * 每条记录按 GM/T 0034 §10.1/§10.5 做执行人数字签名（SM3withSM2），日志只增不删改；
 * 并形成哈希链（上一条签名纳入本条规范化串，行业增强实践）——任何一条被改动都会断链且验签失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /** 微秒精度：与 created_at DATETIME(6) 列严格对齐，避免毫秒四舍五入导致验签失败 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** 链对账分页扫描批大小（全量对账时控制内存占用） */
    private static final int CHAIN_SCAN_PAGE = 500;

    /** detail 列宽（VARCHAR(500)）：超长先截断，避免严格模式下插入失败而丢留痕 */
    private static final int DETAIL_MAX_LEN = 500;

    private final AuditLogMapper auditLogMapper;
    private final AuditSigner auditSigner;

    /** 记录一条审计日志（自动签名防篡改 + 哈希链） */
    @Transactional(rollbackFor = Exception.class)
    public void record(String username, String action, String target, String detail, String ip) {
        AuditLog logRow = new AuditLog();
        logRow.setUsername(username == null || username.isBlank() ? "SYSTEM" : username);
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

    /** 取并锁定链头记录；加锁失败降级为非锁定读（审计留痕优先于严格串行） */
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

    /** 审计记录规范化串（签名与验签共用同一格式） */
    public static String canonical(AuditLog logRow) {
        String base = String.join("|",
                nz(logRow.getUsername()), nz(logRow.getAction()), nz(logRow.getTarget()),
                nz(logRow.getDetail()), nz(logRow.getIp()),
                logRow.getCreatedAt() == null ? "" : TS.format(logRow.getCreatedAt()));
        return logRow.getPrevSign() == null || logRow.getPrevSign().isBlank()
                ? base : base + "|" + logRow.getPrevSign();
    }

    /** 兼容验签：优先新格式（含哈希链段），失败再按旧格式（无 prevSign 段）校验，保证 P0 老记录仍可验 */
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

    /** 验签：按 id 读取记录并校验签名；返回 {valid, canonical, sign, prevSign}（改过任一字段即失败） */
    public Map<String, Object> verifyLog(Long id) {
        AuditLog logRow = auditLogMapper.selectById(id);
        if (logRow == null) {
            throw new IllegalArgumentException("审计记录不存在（id=" + id + "）");
        }
        String canon = canonical(logRow);
        boolean valid = verifyCompat(logRow);
        String chainNote = valid && logRow.getPrevSign() != null && !logRow.getPrevSign().isBlank()
                ? "，且哈希链指向的记录签名一致" : "";
        return Map.of(
                "id", id,
                "valid", valid,
                "message", valid ? "签名有效：记录未被篡改，由审计签名密钥签发" + chainNote
                        : "签名校验失败：记录内容已被改动或签名缺失",
                "canonical", canon,
                "sign", logRow.getSign() == null ? "" : logRow.getSign(),
                "prevSign", logRow.getPrevSign() == null ? "" : logRow.getPrevSign());
    }

    /**
     * 哈希链对账：按 id 升序（分页扫描）逐条核验
     * a) 自签名有效（走 verifyCompat，兼容 P0 无链段的老记录）；
     * b) 非链首记录的 prev_id 指向的记录必须存在、且等于扫描序前一条的 id、且本条 prev_sign 等于该记录的 sign。
     *
     * <p>威胁模型：攻击者删除中间记录后无法伪造签名补链（他拿不到审计私钥），
     * 但删除会把链剪成 prev_id 指向不存在的空洞——对账正是靠 prev_id 指向缺失检出删除，而非靠重算签名。</p>
     *
     * @param fromId 起始记录ID（含，可空=从头开始）
     * @param toId   结束记录ID（含，可空=到最新）
     * @return {checked, broken:[{id, reason}]}
     */
    public Map<String, Object> verifyChain(Long fromId, Long toId) {
        List<Map<String, Object>> broken = new ArrayList<>();
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
        return Map.of("checked", checked, "broken", broken);
    }

    /** 单条记录的对账判定（前驱信息尽量复用扫描序上一条，避免逐条回查） */
    private void checkChainRow(AuditLog row, AuditLog prevRow, List<Map<String, Object>> broken) {
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

    /** 取 id 严格小于给定 id 的最近一条记录（区间起点的真实前驱） */
    private AuditLog loadPredecessor(Long id) {
        return auditLogMapper.selectOne(new LambdaQueryWrapper<AuditLog>()
                .lt(AuditLog::getId, id).orderByDesc(AuditLog::getId).last("LIMIT 1"));
    }

    private static Map<String, Object> brokenItem(Long id, String reason) {
        return Map.of("id", id, "reason", reason);
    }

    /** 分页查询审计日志 */
    public Page<AuditLog> page(long pageNo, long pageSize, String action) {
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (action != null && !action.isBlank()) {
            qw.eq(AuditLog::getAction, action);
        }
        qw.orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /**
     * 审计归档导出（留存 ≥5 年材料，等保要求）：全量日志导出 CSV（UTF-8 BOM），
     * 并对导出内容做审计 SM2 报告签名（§10.5 审计记录创建人数字签名延伸）——归档文件完整性可在线验签。
     *
     * @return {csv, sign, count}：sign 是对 CSV 全文的 SM3withSM2 签名（hex），验签=对下载的 CSV 内容重算比对
     */
    public Map<String, Object> exportCsv() {
        List<AuditLog> logs = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .orderByAsc(AuditLog::getId));
        StringBuilder sb = new StringBuilder("\uFEFF"); // UTF-8 BOM，Excel 直接识别中文
        sb.append("id,username,action,target,detail,ip,created_at,prev_sign,sign\n");
        for (AuditLog l : logs) {
            sb.append(l.getId()).append(',')
                    .append(csv(l.getUsername())).append(',')
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
        log.info("[审计] 归档导出完成，记录数={}，报告已签名", logs.size());
        return Map.of("csv", csv, "sign", sign, "count", logs.size());
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

    /** 超长详情截断到列宽内（保留可读尾部标记，签名基于截断后的值，验签自洽） */
    private static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_MAX_LEN) {
            return detail;
        }
        return detail.substring(0, DETAIL_MAX_LEN - 1) + "…";
    }
}
