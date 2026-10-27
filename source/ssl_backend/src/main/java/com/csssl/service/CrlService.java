package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
import com.csssl.util.PemUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 吊销列表（CRL）：按需从已吊销证书记录实时生成，由服务签发二级CA（SUB_CA）私钥签名。
 * CRL 携带 thisUpdate/nextUpdate（标准 X.509 CRL 字段），依赖方可据此做吊销缓存与发现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrlService {

    private final CaCertMapper certMapper;
    private final CaService caService;

    /** 应纳入 CRL 的证书：已吊销（REVOKED）+ 挂起（FROZEN，reason=certificateHold）；排除 ROOT/SUB_CA（由上一级CA管理） */
    public List<CaCert> revokedList() {
        return certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .in(CaCert::getStatus, "REVOKED", "FROZEN")
                .notIn(CaCert::getCertType, CaService.TYPE_ROOT, CaService.TYPE_SUB_CA)
                .orderByDesc(CaCert::getRevokedAt));
    }

    /** 生成 CRL（X509 CRL v2，PEM 文本） */
    public String buildCrlPem() {
        return buildCrl().pem();
    }

    /** 生成 CRL 并附带 thisUpdate/nextUpdate（管理端展示与依赖方缓存用） */
    public CrlInfo buildCrl() {
        List<CaCert> revoked = revokedList();
        List<CertUtil.CrlEntry> entries = new ArrayList<>();
        for (CaCert c : revoked) {
            Date revokedAt = c.getRevokedAt() == null ? new Date()
                    : Date.from(c.getRevokedAt().atZone(ZoneId.systemDefault()).toInstant());
            entries.add(new CertUtil.CrlEntry(c.getSerialHex(), revokedAt,
                    CertUtil.crlReasonCode(c.getRevokeReason())));
        }
        X509Certificate caCert = caService.signingCert();
        PrivateKey caKey = caService.signingKey();
        java.security.cert.X509CRL crl = CertUtil.buildCrl(caCert, caKey, entries);
        CrlInfo info = new CrlInfo(PemUtil.crlToPem(crl),
                crl.getThisUpdate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                crl.getNextUpdate().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                entries.size(), CertUtil.subjectCn(caCert));
        log.info("[CRL] 生成完成，吊销条目数={}, thisUpdate={}, nextUpdate={}",
                info.entries(), info.thisUpdate(), info.nextUpdate());
        return info;
    }

    /** CRL 详情（PEM + 生效/下次更新时间 + 条目数 + 签发者） */
    public record CrlInfo(String pem, LocalDateTime thisUpdate, LocalDateTime nextUpdate,
                          int entries, String issuerCn) {
    }
}
