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
 * OCSP 在线证书状态查询（RFC 6960）：标准三态 responder，按机构域独立。
 * <ul>
 *   <li>good：证书有效（VALID/EXPIRED 均视为在本CA记录中且未吊销；按规范仅区分已吊销/未知/良好）</li>
 *   <li>revoked：证书已吊销（附带吊销时间与原因码）</li>
 *   <li>unknown：本机构域未签发的证书（serial 命中但归属其他机构同样按 unknown，域间互不代答）</li>
 * </ul>
 * 响应由请求 orgKey 对应机构的服务签发二级CA（SUB_CA）私钥签名，依赖方可用该机构 SUB_CA/根证书验签。
 * 传输：POST（application/ocsp-request）与 GET（/ocsp/{base64url}）两种标准形式；
 * 无 orgKey 入口（respond(byte[])）委托 default 域，现存端点行为不变。
 * 合规点：SUSPENDED/CLOSED 机构历史证书必须可继续查询状态，本类不做机构状态门禁（只读端点不因机构关闭）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcspService {

    private final CaCertMapper certMapper;
    private final CaService caService;
    private final CssslProperties props;

    /** 响应 OCSP 请求（DER 输入 → DER 输出，兼容入口=default 机构域） */
    public byte[] respond(byte[] requestDer) {
        return respond(CaService.DEFAULT_ORG_KEY, requestDer);
    }

    /** 机构域 responder：按 orgKey 定位机构域查询吊销状态，用该机构 SUB_CA 密钥签响应 */
    public byte[] respond(String orgKey, byte[] requestDer) {
        try {
            OCSPReq req = new OCSPReq(requestDer);
            Req[] reqs = req.getRequestList();
            if (reqs == null || reqs.length == 0) {
                throw new IllegalArgumentException("OCSP请求为空");
            }
            CertificateID certId = reqs[0].getCertID();
            BigInteger serial = certId.getSerialNumber();

            // 域内查询：仅本机构签发的证书参与三态判定（存量行 org_key='default'，default 域行为不变）
            CaCert row = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                    .eq(CaCert::getSerialHex, serial.toString(16))
                    .eq(CaCert::getOrgKey, orgKey));

            X509Certificate signerCert = caService.signingCert(orgKey);
            PrivateKey signerKey = caService.signingKey(orgKey);
            JcaX509CertificateHolder signerHolder = new JcaX509CertificateHolder(signerCert);
            // responder 身份 = 该机构服务签发二级CA（SUB_CA），响应由同一私钥签名
            BasicOCSPRespBuilder builder = new BasicOCSPRespBuilder(signerHolder.getSubjectPublicKeyInfo(),
                    new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1));
            Date thisUpdate = new Date();
            // nextUpdate 时效可配置（csssl.ocsp-next-update-hours，默认24h；行业1~7天）
            Date nextUpdate = Date.from(LocalDateTime.now().plusHours(props.getOcspNextUpdateHours())
                    .atZone(ZoneId.systemDefault()).toInstant());

            CertificateStatus status;
            if (row == null) {
                status = new UnknownStatus();
                log.info("[OCSP][{}] serial={} -> unknown（非本机构证书）", orgKey, serial);
            } else if ("REVOKED".equals(row.getStatus()) || "FROZEN".equals(row.getStatus())) {
                Date revokedAt = row.getRevokedAt() == null ? new Date()
                        : Date.from(row.getRevokedAt().atZone(ZoneId.systemDefault()).toInstant());
                status = new RevokedStatus(revokedAt, CertUtil.crlReasonCode(row.getRevokeReason()));
                log.info("[OCSP][{}] serial={} -> revoked（{}）", orgKey, serial,
                        "FROZEN".equals(row.getStatus()) ? "CERTIFICATE_HOLD(挂起)" : row.getRevokeReason());
            } else {
                status = CertificateStatus.GOOD;
                log.info("[OCSP][{}] serial={} -> good（{}）", orgKey, serial, row.getSubjectCn());
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
