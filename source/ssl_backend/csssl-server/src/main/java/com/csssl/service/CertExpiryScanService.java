package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaCert;
import com.csssl.entity.Message;
import com.csssl.entity.SysUser;
import com.csssl.enums.RoleCodeEnum;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.MessageMapper;
import com.csssl.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 证书到期巡检：每日扫 {@code ssl_ca_cert}，对「还有 N 天到期 / 已过期」的证书给 CA 管理角色发站内信。
 *
 * <p>为什么需要它：证书有效期是签进证书本体、后续不可更改的属性，链路一旦依赖它做报文验签，
 * 到期那天表现为「所有报文验签突然失败」，且与网络、时钟、密钥等问题难以区分。现在库里只有
 * {@code valid_to} 字段，没有任何主动提醒路径，只能靠人记得。</p>
 *
 * <p>口径：</p>
 * <ul>
 *   <li>只扫 {@code VALID} / {@code FROZEN}（挂起证书到期后恢复仍需有效），已吊销/已过期的不再提醒；</li>
 *   <li>提前档位由 {@code csssl.cert-expiry-warn-days} 配置（默认 {@code 30,7}），命中最近一档即提醒；</li>
 *   <li>同一证书同一档位在 {@code csssl.cert-expiry-dedup-days} 天内只提醒一次（查 {@code ssl_message}，
 *       不新增去重表）；</li>
 *   <li>收件人取启用账号中 ADMIN / SECURITY_ADMIN / COMPANY_ADMIN 三类角色：前者巡检、中者管策略、
 *       后者管公司与接入方，证书到期正好落在这三方的职责上；</li>
 *   <li>全程单条失败不影响其它证书，整轮失败只记日志——巡检是旁路能力，不允许拖垮主流程。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertExpiryScanService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> LIVE_STATUS = List.of("VALID", "FROZEN");
    private static final String BIZ_TYPE = "CA_CERT";
    private static final Set<String> NOTIFY_ROLES = Set.of(
            RoleCodeEnum.ADMIN.name(),
            RoleCodeEnum.SECURITY_ADMIN.name(),
            RoleCodeEnum.COMPANY_ADMIN.name());

    private final CaCertMapper certMapper;
    private final SysUserMapper userMapper;
    private final MessageMapper messageMapper;
    private final MessageService messageService;

    /** 提前提醒档位（天），逗号分隔 */
    @Value("${csssl.cert-expiry-warn-days:30,7}")
    private String warnDaysSpec;

    /** 同一证书同一档位的提醒间隔（天） */
    @Value("${csssl.cert-expiry-dedup-days:7}")
    private int dedupDays;

    @Scheduled(cron = "${csssl.cert-expiry-cron:0 0 9 * * ?}")
    public void scan() {
        List<CaCert> certs;
        try {
            certs = certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                    .in(CaCert::getStatus, LIVE_STATUS)
                    .isNotNull(CaCert::getValidTo)
                    .orderByAsc(CaCert::getValidTo));
        } catch (Exception e) {
            log.warn("[证书到期巡检] 读取证书总账失败，本轮跳过: {}", e.getMessage());
            return;
        }

        List<SysUser> recipients = recipients();
        if (recipients.isEmpty()) {
            log.warn("[证书到期巡检] 无启用的 ADMIN/SECURITY_ADMIN/COMPANY_ADMIN 账号，本轮仅记录日志");
        }

        int sent = 0;
        for (CaCert cert : certs) {
            try {
                sent += scanOne(cert, recipients);
            } catch (Exception e) {
                log.warn("[证书到期巡检] 单条处理失败 serial={}: {}", cert.getSerialHex(), e.getMessage());
            }
        }
        log.info("[证书到期巡检] 完成：在册证书 {} 张，本次发出提醒 {} 条", certs.size(), sent);
    }

    /** 单张证书判定与提醒，返回发出的提醒条数 */
    private int scanOne(CaCert cert, List<SysUser> recipients) {
        long remainDays = ChronoUnit.DAYS.between(LocalDateTime.now(), cert.getValidTo());
        String level = levelOf(remainDays);
        if (level == null) {
            return 0;
        }
        if (recipients.isEmpty()) {
            log.warn("[证书到期巡检] 证书 {}（{}）当前档位 {}，但无收件人可通知",
                    cert.getSerialHex(), cert.getSubjectCn(), level);
            return 0;
        }

        String bizId = cert.getSerialHex() + "#" + level;
        if (alreadyNotified(bizId)) {
            return 0;
        }

        String title = remainDays > 0
                ? "【证书到期】" + nz(cert.getSubjectCn()) + " 还有 " + remainDays + " 天到期"
                : "【证书已过期】" + nz(cert.getSubjectCn()) + " 已过期 " + (-remainDays) + " 天";
        String content = describe(cert, remainDays);

        int n = 0;
        for (SysUser u : recipients) {
            try {
                messageService.sendAs(cert.getCompanyUscc(), u.getId(), u.getRealName(),
                        null, null, "CERT_EXPIRING", title, content, BIZ_TYPE, bizId);
                n++;
            } catch (Exception e) {
                log.warn("[证书到期巡检] 通知 {} 失败: {}", u.getUsername(), e.getMessage());
            }
        }
        log.info("[证书到期巡检] serial={} CN={} 剩余 {} 天，档位 {}，已通知 {} 人",
                cert.getSerialHex(), cert.getSubjectCn(), remainDays, level, n);
        return n;
    }

    /**
     * 判定提醒档位：已过期返回 {@code EXPIRED}；否则取「大于剩余天数的最小档位」，
     * 都不命中返回 null（还没进入提醒窗口）。
     */
    private String levelOf(long remainDays) {
        if (remainDays <= 0) {
            return "EXPIRED";
        }
        for (int days : warnDays()) {
            if (remainDays <= days) {
                return "D" + days;
            }
        }
        return null;
    }

    /** 配置档位解析：升序去重，忽略非法值 */
    private List<Integer> warnDays() {
        TreeSet<Integer> set = new TreeSet<>();
        for (String s : warnDaysSpec.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int d = Integer.parseInt(t);
                if (d > 0) {
                    set.add(d);
                }
            } catch (NumberFormatException e) {
                log.warn("[证书到期巡检] 档位配置非法，已忽略: {}", t);
            }
        }
        if (set.isEmpty()) {
            set.add(30);
            set.add(7);
        }
        return new ArrayList<>(set);
    }

    private boolean alreadyNotified(String bizId) {
        if (dedupDays <= 0) {
            return false;
        }
        try {
            Long cnt = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                    .eq(Message::getBizType, BIZ_TYPE)
                    .eq(Message::getBizId, bizId)
                    .ge(Message::getCreatedAt, LocalDateTime.now().minusDays(dedupDays)));
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            // 去重查询失败按「未提醒过」处理：宁可重复一条，也不能漏掉到期提醒
            log.warn("[证书到期巡检] 去重查询失败 bizId={}，按未提醒处理: {}", bizId, e.getMessage());
            return false;
        }
    }

    private List<SysUser> recipients() {
        try {
            return userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getStatus, 1)
                    .in(SysUser::getRoleCode, NOTIFY_ROLES));
        } catch (Exception e) {
            log.warn("[证书到期巡检] 收件人查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String describe(CaCert cert, long remainDays) {
        StringBuilder sb = new StringBuilder();
        sb.append("证书序列号 ").append(nz(cert.getSerialHex()))
                .append("（CN=").append(nz(cert.getSubjectCn()))
                .append("，类型=").append(nz(String.valueOf(cert.getCertType()))).append("）");
        sb.append("有效期至 ").append(cert.getValidTo() == null ? "-" : cert.getValidTo().format(FMT));
        if (remainDays > 0) {
            sb.append("，剩余 ").append(remainDays).append(" 天");
        } else {
            sb.append("，已过期 ").append(-remainDays).append(" 天");
        }
        sb.append("。公司域=").append(nz(cert.getCompanyUscc()))
                .append("，系统=").append(nz(cert.getSystemName()));
        if (cert.getDescription() != null && !cert.getDescription().isBlank()) {
            sb.append("，用途=").append(cert.getDescription());
        }
        sb.append("。续期请走 RA 申请→审批→签发，签发后把新证书替换到对端并重跑一次联调。");
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
