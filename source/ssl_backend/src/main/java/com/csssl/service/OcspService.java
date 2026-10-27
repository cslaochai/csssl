package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.CssslProperties;
import com.csssl.entity.CaCert;
import com.csssl.mapper.CaCertMapper;
import com.csssl.util.CertUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * OCSP 在线证书状态查询（RFC 6960）：标准三态 responder。
 * <ul>
 *   <li>good：证书有效（VALID/EXPIRED 均视为在本CA记录中且未吊销；按规范仅区分已吊销/未知/良好）</li>
 *   <li>revoked：证书已吊销（附带吊销时间与原因码）</li>
 *   <li>unknown：本CA未签发的证书</li>
 * </ul>
 * 响应由服务签发二级CA（SUB_CA）私钥签名，依赖方可用 SUB_CA/根证书验签。
 * 传输：POST（application/ocsp-request）与 GET（/ocsp/{base64url}）两种标准形式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcspService {

    private final CaCertMapper certMapper;
    private final CaService caService;
    private final CssslProperties props;

    /** 响应 OCSP 请求（DER 输入 → DER 输出） */
    public byte[] respond(byte[] requestDer) {
        try {
            OCSPReq req = new OCSPReq(requestDer);
            Req[] reqs = req.getRequestList();
            if (reqs == null || reqs.length == 0) {
                throw new IllegalArgumentException("OCSP请求为空");
            }
            CertificateID certId = reqs[0].getCertID();
            BigInteger serial = certId.getSerialNumber();

            CaCert row = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                    .eq(CaCert::getSerialHex, serial.toString(16)));

            X509Certificate signerCert = caService.signingCert();
            PrivateKey signerKey = caService.signingKey();
            JcaX509CertificateHolder signerHolder = new JcaX509CertificateHolder(signerCert);
            // responder 身份 = 服务签发二级CA（SUB_CA），响应由同一私钥签名
            BasicOCSPRespBuilder builder = new BasicOCSPRespBuilder(signerHolder.getSubjectPublicKeyInfo(),
                    new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1));
            Date thisUpdate = new Date();
            // nextUpdate 时效可配置（csssl.ocsp-next-update-hours，默认24h；行业1~7天）
            Date nextUpdate = Date.from(LocalDateTime.now().plusHours(props.getOcspNextUpdateHours())
                    .atZone(ZoneId.systemDefault()).toInstant());

            CertificateStatus status;
            if (row == null) {
                status = new UnknownStatus();
                log.info("[OCSP] serial={} -> unknown（非本站证书）", serial);
            } else if ("REVOKED".equals(row.getStatus()) || "FROZEN".equals(row.getStatus())) {
                Date revokedAt = row.getRevokedAt() == null ? new Date()
                        : Date.from(row.getRevokedAt().atZone(ZoneId.systemDefault()).toInstant());
                status = new RevokedStatus(revokedAt, CertUtil.crlReasonCode(row.getRevokeReason()));
                log.info("[OCSP] serial={} -> revoked（{}）", serial,
                        "FROZEN".equals(row.getStatus()) ? "CERTIFICATE_HOLD(挂起)" : row.getRevokeReason());
            } else {
                status = CertificateStatus.GOOD;
                log.info("[OCSP] serial={} -> good（{}）", serial, row.getSubjectCn());
            }
            builder.addResponse(certId, status, thisUpdate, nextUpdate, null);

            ContentSigner signer = new JcaContentSignerBuilder(CertUtil.signatureAlgorithmForKey(signerKey))
                    .setProvider("BC").build(signerKey);
            BasicOCSPResp basic = builder.build(signer, new X509CertificateHolder[]{signerHolder}, thisUpdate);
            OCSPResp resp = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basic);
            return resp.getEncoded();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OCSP响应失败: " + e.getMessage(), e);
        }
    }
}
