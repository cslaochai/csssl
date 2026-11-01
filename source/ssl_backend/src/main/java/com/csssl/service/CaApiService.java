package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.ApiException;
import com.csssl.config.CssslProperties;
import com.csssl.dto.ApiApplicationSubmitDTO;
import com.csssl.dto.ApiCertRevokeDTO;
import com.csssl.dto.ApplicationSubmitDTO;
import com.csssl.entity.*;
import com.csssl.enums.CertApplicationStatusEnum;
import com.csssl.mapper.CaCertMapper;
import com.csssl.mapper.CertApplicationMapper;
import com.csssl.mapper.CertTemplateMapper;
import com.csssl.util.PemUtil;
import com.csssl.vo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 对外证书申请 API（HIS 等外部系统直连 RA）。
 *
 * <p>数据域铁律：租户一律取 {@link ApiClient#getTenantUscc()}（由 HMAC 鉴权后的 appId 反查），
 * 请求 URL 与请求体都不接受租户参数；客户端只能看到自己（同 appId）提交的申请单。</p>
 *
 * <p>幂等：同租户同 appId 下 externalRequestId 唯一，请求体 SHA-256 作为指纹；
 * 同 ID 同体返回首次结果（idempotentReplay=true），同 ID 异体 409。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaApiService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CertApplicationMapper applicationMapper;
    private final CertTemplateMapper templateMapper;
    private final CaCertMapper certMapper;
    private final CertApplicationService applicationService;
    private final CertService certService;
    private final CaService caService;
    private final CssslProperties props;

    private static String apiUsername(ApiClient client) {
        return "api:" + client.getAppId();
    }

    private static String applicationStatusText(String status) {
        if (status == null) {
            return "未知";
        }
        CertApplicationStatusEnum s = CertApplicationStatusEnum.fromCode(status);
        return s == null ? status : s.getLabel();
    }

    private static String certStatusText(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "VALID" -> "有效";
            case "REVOKED" -> "已吊销";
            case "FROZEN" -> "已挂起";
            case "EXPIRED" -> "已过期";
            default -> status;
        };
    }

    private static String normalizeSerial(String serialHex) {
        if (serialHex == null || serialHex.isBlank()) {
            throw new ApiException(400, "serialHex 不能为空");
        }
        String serial = serialHex.replace(":", "").replace(" ", "").trim().toLowerCase();
        if (!serial.matches("[0-9a-f]+")) {
            throw new ApiException(400, "serialHex 必须是十六进制字符串");
        }
        return serial;
    }

    private static String fingerprint(X509Certificate cert) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (!sb.isEmpty()) {
                    sb.append(':');
                }
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 信任材料：根/中间证书 PEM 与 CRL/OCSP 地址（按 appId 绑定租户生成，调用方无需知道租户标识）。
     */
    public ApiTrustVO trust(ApiClient client) {
        String tenant = client.getTenantUscc();
        String base = props.getPublicBaseUrl().replaceAll("/+$", "");
        ApiTrustVO vo = new ApiTrustVO();
        vo.setRootPem(caService.exportRootPem(tenant));
        vo.setSubCaPem(caService.exportSubCaPem(tenant));
        vo.setCrlUrl(base + "/ca/public/" + tenant + "/crl");
        vo.setDeltaCrlUrl(base + "/ca/public/" + tenant + "/crl/delta");
        vo.setOcspUrl(base + "/ca/public/" + tenant + "/ocsp");
        return vo;
    }

    /**
     * 可用模板：全局模板 ∪ 本租户模板，排除 SUB_CA（二级CA不走 RA）。
     */
    public List<ApiTemplateVO> templates(ApiClient client, String certType) {
        String tenant = client.getTenantUscc();
        LambdaQueryWrapper<CertTemplate> qw = new LambdaQueryWrapper<CertTemplate>()
                .and(w -> w.isNull(CertTemplate::getTenantUscc).or().eq(CertTemplate::getTenantUscc, tenant))
                .ne(CertTemplate::getCertType, "SUB_CA");
        if (certType != null && !certType.isBlank()) {
            String type = certType.trim().toUpperCase();
            if (!"SERVER".equals(type) && !"CLIENT".equals(type)) {
                throw new ApiException(400, "certType 仅支持 SERVER / CLIENT");
            }
            qw.eq(CertTemplate::getCertType, type);
        }
        qw.orderByAsc(CertTemplate::getTemplateCode);
        List<ApiTemplateVO> rows = new ArrayList<>();
        for (CertTemplate tpl : templateMapper.selectList(qw)) {
            rows.add(ApiTemplateVO.from(tpl));
        }
        return rows;
    }

    /**
     * 提交申请：幂等命中即返回首次结果；否则经 RA 同一套校验落 PENDING 单。
     */
    public ApiApplicationVO submit(ApiClient client, ApiApplicationSubmitDTO body, String requestHash, String ip) {
        String tenant = client.getTenantUscc();
        CertApplication existing = findExternal(tenant, client.getAppId(), body.getExternalRequestId());
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }
        CertTemplate tpl = resolveTemplate(tenant, body.getTemplateCode());

        ApplicationSubmitDTO dto = new ApplicationSubmitDTO();
        dto.setTenantUscc(tenant);
        dto.setApplicantType(body.getApplicantType());
        dto.setApplicantName(body.getApplicantName());
        dto.setSubjectDn(body.getSubjectDn());
        dto.setTemplateId(tpl.getId());
        dto.setPublicKeyPem(body.getPublicKeyPem());
        dto.setSan(body.getSan());
        dto.setDescription(body.getDescription());

        CertApplication app;
        try {
            app = applicationService.submitExternal(dto, client.getAppId(), body.getExternalRequestId(),
                    requestHash, body.getExternalEmployeeId(), body.getExternalEmployeeCode(),
                    body.getExternalDeptName(), apiUsername(client), client.getClientName(), ip, tenant);
        } catch (DuplicateKeyException e) {
            // 并发同 ID 提交：唯一键兜底，以先落库的单为准（同体重放/异体冲突）
            CertApplication winner = findExternal(tenant, client.getAppId(), body.getExternalRequestId());
            if (winner == null) {
                throw e;
            }
            return replayOrConflict(winner, requestHash);
        }
        log.info("[对外API][{}] 提交申请 appId={}, applyNo={}, externalRequestId={}",
                tenant, client.getAppId(), app.getApplyNo(), body.getExternalRequestId());
        return toVo(app, false);
    }

    /**
     * 按申请单号查询（仅本客户端提交的单）
     */
    public ApiApplicationVO detail(ApiClient client, String applyNo) {
        CertApplication app = requireApplication(client, applyNo);
        return toVo(app, false);
    }

    /**
     * 按 externalRequestId 查询（仅本客户端提交的单）
     */
    public ApiApplicationVO detailByExternalRequestId(ApiClient client, String externalRequestId) {
        CertApplication app = findExternal(client.getTenantUscc(), client.getAppId(), externalRequestId);
        if (app == null) {
            throw new ApiException(404, "申请单不存在（externalRequestId=" + externalRequestId + "）");
        }
        return toVo(app, false);
    }

    /**
     * 撤销申请（仅提交方；仅 PENDING/APPROVED 可撤销）
     */
    public ApiApplicationVO cancel(ApiClient client, String applyNo, String reason, String ip) {
        CertApplication app = requireApplication(client, applyNo);
        if (CertApplicationStatusEnum.CANCELLED.is(app.getStatus())) {
            return toVo(app, false);
        }
        if (!CertApplicationStatusEnum.PENDING.is(app.getStatus())
                && !CertApplicationStatusEnum.APPROVED.is(app.getStatus())) {
            throw new ApiException(409, "申请单当前状态为 " + applicationStatusText(app.getStatus()) + "，不可撤销");
        }
        CertApplication cancelled = applicationService.cancel(app.getId(), apiUsername(client), null, ip,
                client.getTenantUscc(), reason);
        return toVo(cancelled, false);
    }

    /**
     * 证书下载：仅 ISSUED 单；chain=true（默认）返回 叶子+SUB_CA+根，false 仅叶子。
     */
    public CertificateDownload download(ApiClient client, String applyNo, boolean includeChain) {
        CertApplication app = requireApplication(client, applyNo);
        if (!CertApplicationStatusEnum.ISSUED.is(app.getStatus()) || app.getIssuedCertId() == null) {
            throw new ApiException(409, "申请单当前状态为 " + applicationStatusText(app.getStatus()) + "，证书尚未签发");
        }
        CaCert cert = requireCertOfTenant(app.getIssuedCertId(), client.getTenantUscc());
        X509Certificate leaf;
        try {
            leaf = PemUtil.parseCert(cert.getCertPem());
        } catch (Exception e) {
            throw new IllegalStateException("证书内容无法解析（serial=" + cert.getSerialHex() + "）", e);
        }
        String pem = cert.getCertPem();
        if (includeChain) {
            pem = certService.certPem(cert.getId()) + caService.exportRootPem(client.getTenantUscc());
        }
        return new CertificateDownload(pem, cert.getSerialHex(), fingerprint(leaf), TS.format(cert.getValidTo()));
    }

    /**
     * 按序列号查询证书状态（本租户域内；实时性要求高时以 OCSP 为准）
     */
    public ApiCertStatusVO certStatus(ApiClient client, String serialHex) {
        CaCert cert = requireCertBySerial(client.getTenantUscc(), serialHex);
        return statusVo(cert);
    }

    /**
     * 吊销证书（外部系统主动推送吊销）：序列号与原因都在请求体内，已吊销按幂等返回。
     */
    public ApiCertStatusVO revoke(ApiClient client, ApiCertRevokeDTO body, String ip) {
        CaCert cert = requireCertBySerial(client.getTenantUscc(), body.getSerialHex());
        if ("REVOKED".equals(cert.getStatus())) {
            log.info("[对外API][{}] 吊销幂等命中 appId={}, serial={}",
                    client.getTenantUscc(), client.getAppId(), cert.getSerialHex());
            return statusVo(cert);
        }
        String reason = body.getReason() == null || body.getReason().isBlank()
                ? "外部系统推送吊销" : body.getReason().trim();
        certService.revoke(cert.getId(), reason, apiUsername(client), ip);
        ApiCertStatusVO vo = statusVo(certService.findById(cert.getId()));
        log.info("[对外API][{}] 吊销证书 appId={}, serial={}, reason={}",
                client.getTenantUscc(), client.getAppId(), cert.getSerialHex(), reason);
        return vo;
    }

    /**
     * 按序列号定位本租户证书（外部 API 的吊销与状态查询共用域校验）
     */
    private CaCert requireCertBySerial(String tenant, String serialHex) {
        String serial = normalizeSerial(serialHex);
        CaCert cert = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getTenantUscc, tenant)
                .eq(CaCert::getSerialHex, serial)
                .last("LIMIT 1"));
        if (cert == null) {
            throw new ApiException(404, "证书不存在（serialHex=" + serialHex + "）");
        }
        return cert;
    }

    private ApiCertStatusVO statusVo(CaCert cert) {
        ApiCertStatusVO vo = new ApiCertStatusVO();
        vo.setSerialHex(cert.getSerialHex());
        vo.setStatus(cert.getStatus());
        vo.setStatusText(certStatusText(cert.getStatus()));
        vo.setRevokedAt(cert.getRevokedAt());
        vo.setRevokeReason(cert.getRevokeReason());
        vo.setValidFrom(cert.getValidFrom());
        vo.setValidTo(cert.getValidTo());
        return vo;
    }

    /**
     * 模板解析：本租户模板优先于全局模板；SUB_CA 模板不允许走 RA。
     */
    private CertTemplate resolveTemplate(String tenant, String templateCode) {
        List<CertTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<CertTemplate>()
                .eq(CertTemplate::getTemplateCode, templateCode)
                .and(w -> w.isNull(CertTemplate::getTenantUscc).or().eq(CertTemplate::getTenantUscc, tenant))
                .orderByDesc(CertTemplate::getTenantUscc));
        if (rows.isEmpty()) {
            throw new ApiException(422, "模板不存在或对本租户不可用：" + templateCode);
        }
        CertTemplate tpl = rows.get(0);
        if ("SUB_CA".equals(tpl.getCertType())) {
            throw new ApiException(422, "SUB_CA 证书不走申请流程，请联系 CA 运维");
        }
        return tpl;
    }

    private CertApplication findExternal(String tenant, String appId, String externalRequestId) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<CertApplication>()
                .eq(CertApplication::getTenantUscc, tenant)
                .eq(CertApplication::getExternalSystem, appId)
                .eq(CertApplication::getExternalRequestId, externalRequestId)
                .last("LIMIT 1"));
    }

    private CertApplication requireApplication(ApiClient client, String applyNo) {
        CertApplication app = applicationMapper.selectOne(new LambdaQueryWrapper<CertApplication>()
                .eq(CertApplication::getTenantUscc, client.getTenantUscc())
                .eq(CertApplication::getExternalSystem, client.getAppId())
                .eq(CertApplication::getApplyNo, applyNo)
                .last("LIMIT 1"));
        if (app == null) {
            throw new ApiException(404, "申请单不存在（applyNo=" + applyNo + "）");
        }
        return app;
    }

    private CaCert requireCertOfTenant(Long certId, String tenant) {
        CaCert cert = certService.findById(certId);
        String rowTenant = cert.getTenantUscc() == null || cert.getTenantUscc().isBlank()
                ? CaTenant.PRIMARY_TENANT_USCC : cert.getTenantUscc();
        if (!tenant.equals(rowTenant)) {
            throw new ApiException(403, "证书不属于当前客户端租户");
        }
        return cert;
    }

    private ApiApplicationVO replayOrConflict(CertApplication existing, String requestHash) {
        if (existing.getExternalRequestHash() == null
                || !existing.getExternalRequestHash().equalsIgnoreCase(requestHash)) {
            throw new ApiException(409, "幂等冲突：externalRequestId 已用于不同请求体");
        }
        return toVo(existing, true);
    }

    private ApiApplicationVO toVo(CertApplication app, boolean replay) {
        ApiApplicationVO vo = new ApiApplicationVO();
        vo.setApplyNo(app.getApplyNo());
        vo.setExternalRequestId(app.getExternalRequestId());
        vo.setApplicantType(app.getApplicantType());
        vo.setApplicantName(app.getApplicantName());
        vo.setSubjectDn(app.getSubjectDn());
        vo.setTemplateCode(templateCodeOf(app.getTemplateId()));
        vo.setStatus(app.getStatus());
        vo.setStatusText(applicationStatusText(app.getStatus()));
        vo.setSubmittedAt(app.getAppliedAt());
        vo.setReviewedAt(app.getReviewedAt());
        vo.setReviewedByName(app.getReviewedByName());
        vo.setReviewComment(app.getReviewComment());
        vo.setIdempotentReplay(replay);
        if (CertApplicationStatusEnum.ISSUED.is(app.getStatus()) && app.getIssuedCertId() != null) {
            CaCert cert = certMapper.selectById(app.getIssuedCertId());
            if (cert != null) {
                vo.setIssuedAt(cert.getCreatedAt());
                vo.setCertificate(toCertificateVo(cert));
            }
        }
        return vo;
    }

    private ApiCertificateVO toCertificateVo(CaCert cert) {
        ApiCertificateVO vo = new ApiCertificateVO();
        vo.setSerialHex(cert.getSerialHex());
        vo.setAlgorithm(cert.getAlgorithm());
        vo.setNotBefore(cert.getValidFrom());
        vo.setNotAfter(cert.getValidTo());
        vo.setCertPem(cert.getCertPem());
        vo.setChainPem(certService.certPem(cert.getId()));
        try {
            X509Certificate x509 = PemUtil.parseCert(cert.getCertPem());
            vo.setSubjectDn(x509.getSubjectX500Principal().getName());
            vo.setIssuerDn(x509.getIssuerX500Principal().getName());
            vo.setFingerprintSha256(fingerprint(x509));
        } catch (Exception e) {
            log.warn("[对外API] 证书解析失败 serial={}: {}", cert.getSerialHex(), e.getMessage());
        }
        return vo;
    }

    private String templateCodeOf(Long templateId) {
        if (templateId == null) {
            return null;
        }
        CertTemplate tpl = templateMapper.selectById(templateId);
        return tpl == null ? null : tpl.getTemplateCode();
    }

    /**
     * 证书下载结果（PEM 文本 + 响应头辅助信息）
     */
    public record CertificateDownload(String pem, String serialHex, String fingerprintSha256, String notAfter) {
    }
}
