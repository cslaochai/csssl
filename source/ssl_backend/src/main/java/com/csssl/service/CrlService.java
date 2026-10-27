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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 吊销列表（CRL）：按需从已吊销证书记录实时生成，由根CA私钥签名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrlService {

    private final CaCertMapper certMapper;
    private final CaService caService;

    /** 已吊销证书列表（按吊销时间倒序） */
    public List<CaCert> revokedList() {
        return certMapper.selectList(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getStatus, "REVOKED")
                .orderByDesc(CaCert::getRevokedAt));
    }

    /** 生成 CRL（X509 CRL v2，PEM 文本） */
    public String buildCrlPem() {
        List<CaCert> revoked = revokedList();
        List<CertUtil.CrlEntry> entries = new ArrayList<>();
        for (CaCert c : revoked) {
            Date revokedAt = c.getRevokedAt() == null ? new Date()
                    : Date.from(c.getRevokedAt().atZone(ZoneId.systemDefault()).toInstant());
            entries.add(new CertUtil.CrlEntry(c.getSerialHex(), revokedAt,
                    CertUtil.crlReasonCode(c.getRevokeReason())));
        }
        X509Certificate caCert = caService.loadRootCert();
        PrivateKey caKey = caService.loadRootKey();
        java.security.cert.X509CRL crl = CertUtil.buildCrl(caCert, caKey, entries);
        log.info("[CRL] 生成完成，吊销条目数={}", entries.size());
        return PemUtil.crlToPem(crl);
    }
}
