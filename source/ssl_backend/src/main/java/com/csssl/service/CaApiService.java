package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.ApiException;
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
import java.util.List;

/**
 * 对外证书申请 API（HIS 等外部系统直连 RA）。
 *
 * <p>数据域铁律：公司一律取 {@link ApiClient#getCompanyUscc()}（由 HMAC 鉴权后的 appId 反查），
 * 请求 URL 与请求体都不接受公司参数；客户端只能看到自己（同 appId）提交的申请单。</p>
 *
 * <p>幂等：同公司同 appId 下 externalRequestId 唯一，请求体 SHA-256 作为指纹；
 * 同 ID 同体返回首次结果（idempotentReplay=true），同 ID 异体 409。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaApiService {

    private final CertApplicationMapper applicationMapper;
    private final CertTemplateMapper templateMapper;
    private final CaCertMapper certMapper;
    private final CertApplicationService applicationService;
    private final CertService certService;

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
     * 提交申请：幂等命中即返回首次结果；否则经 RA 同一套校验落 PENDING 单。
     */
    public ApiApplicationVO submit(ApiClient client, ApiApplicationSubmitDTO body, String requestHash, String ip) {
        String company = client.getCompanyUscc();
        CertApplication existing = findExternal(company, client.getAppId(), body.getExternalRequestId());
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }
        CertTemplate tpl = resolveTemplate(company, body.getTemplateCode());

        ApplicationSubmitDTO dto = new ApplicationSubmitDTO();
        dto.setCompanyUscc(company);
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
                    body.getExternalDeptName(), apiUsername(client), client.getClientName(), ip, company);
        } catch (DuplicateKeyException e) {
            // 并发同 ID 提交：唯一键兜底，以先落库的单为准（同体重放/异体冲突）
            CertApplication winner = findExternal(company, client.getAppId(), body.getExternalRequestId());
            if (winner == null) {
                throw e;
            }
            return replayOrConflict(winner, requestHash);
        }
        log.info("[对外API][{}] 提交申请 appId={}, applyNo={}, externalRequestId={}",
                company, client.getAppId(), app.getApplyNo(), body.getExternalRequestId());
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
                client.getCompanyUscc(), reason);
        return toVo(cancelled, false);
    }

    /**
     * 吊销证书（外部系统主动推送吊销）：序列号、医生身份与原因都在请求体内，已吊销按幂等返回。
     *
     * <p>作用域收敛——一次一张，且必须落到"指定医生"上，四道校验缺一不可：
     * <ol>
     *   <li>证书在本公司域内（{@link #requireCertBySerial}）；</li>
     *   <li>证书类型为 CLIENT：服务端证书与 SUB_CA 不允许外部系统吊销；</li>
     *   <li>证书由本 appId 提交的申请单签出：与 {@link #requireApplication} 同口径，
     *       不能再出现"申请单只能看自己的、证书却能吊全院的"越权；</li>
     *   <li>医生身份（工号 / HIS 员工主键）与申请单记录一致。</li>
     * </ol>
     */
    public ApiCertStatusVO revoke(ApiClient client, ApiCertRevokeDTO body, String ip) {
        CaCert cert = requireCertBySerial(client.getCompanyUscc(), body.getSerialHex());
        if ("REVOKED".equals(cert.getStatus())) {
            log.info("[对外API][{}] 吊销幂等命中 appId={}, serial={}",
                    client.getCompanyUscc(), client.getAppId(), cert.getSerialHex());
            return statusVo(cert);
        }
        requireRevocableCertType(cert);
        CertApplication app = requireOwnIssuedApplication(client, cert);
        requireEmployeeMatch(client, app, body);
        String reason = body.getReason().trim();
        certService.revoke(cert.getId(), reason, apiUsername(client), ip);
        ApiCertStatusVO vo = statusVo(certService.findById(cert.getId()));
        log.info("[对外API][{}] 吊销证书 appId={}, serial={}, applyNo={}, employee={}, reason={}",
                client.getCompanyUscc(), client.getAppId(), cert.getSerialHex(), app.getApplyNo(),
                body.getExternalEmployeeCode() == null ? body.getExternalEmployeeId() : body.getExternalEmployeeCode(),
                reason);
        return vo;
    }

    /**
     * 按序列号定位本公司证书（外部 API 的吊销与状态查询共用域校验）
     */
    private CaCert requireCertBySerial(String company, String serialHex) {
        String serial = normalizeSerial(serialHex);
        CaCert cert = certMapper.selectOne(new LambdaQueryWrapper<CaCert>()
                .eq(CaCert::getCompanyUscc, company)
                .eq(CaCert::getSerialHex, serial)
                .last("LIMIT 1"));
        if (cert == null) {
            throw new ApiException(404, "证书不存在（serialHex=" + serialHex + "）");
        }
        return cert;
    }

    /**
     * 外部系统只允许吊销客户端（医生）证书：吊服务端证书会直接打断业务，
     * 吊 SUB_CA 会让整片叶子证书失去信任链，二者都必须走 CA 管理端。
     */
    private void requireRevocableCertType(CaCert cert) {
        String type = cert.getCertType();
        if (type != null && !type.isBlank() && !"CLIENT".equalsIgnoreCase(type)) {
            throw new ApiException(403, "该证书类型不允许外部系统吊销（certType=" + type + "），请通过 CA 管理端处理");
        }
    }

    /**
     * 反查签出该证书的申请单并校验归属：只能吊"本 appId 提交的申请单"签出的证书。
     * 证书与申请单是单向关联（application.issuedCertId），这里反向定位。
     */
    private CertApplication requireOwnIssuedApplication(ApiClient client, CaCert cert) {
        CertApplication app = applicationMapper.selectOne(new LambdaQueryWrapper<CertApplication>()
                .eq(CertApplication::getIssuedCertId, cert.getId())
                .last("LIMIT 1"));
        if (app == null) {
            throw new ApiException(403, "证书不是通过对外 API 申请签发的，不能由外部系统吊销（请通过 CA 管理端处理）");
        }
        if (!client.getAppId().equals(app.getExternalSystem())) {
            throw new ApiException(403, "证书不是本客户端（appId=" + client.getAppId() + "）提交的申请单签出的，禁止吊销");
        }
        return app;
    }

    /**
     * 医生身份交叉校验：请求携带的工号 / HIS 员工主键必须与申请单记录一致，
     * 杜绝"拿 A 医生的序列号配 B 医生的身份"吊销。
     *
     * <p>存量申请单若两个身份字段都为空，降级为仅按 appId 归属放行并打告警，
     * 便于数据补齐后收紧；新申请已由 {@link ApiApplicationSubmitDTO#isPersonIdentified()} 强制留痕。
     */
    private void requireEmployeeMatch(ApiClient client, CertApplication app, ApiCertRevokeDTO body) {
        String reqEmployeeId = trimToNull(body.getExternalEmployeeId());
        String reqEmployeeCode = trimToNull(body.getExternalEmployeeCode());
        String recEmployeeId = trimToNull(app.getExternalEmployeeId());
        String recEmployeeCode = trimToNull(app.getExternalEmployeeCode());
        if (recEmployeeId == null && recEmployeeCode == null) {
            log.warn("[对外API][{}] 吊销降级：存量申请单未记录医生身份，仅按 appId 归属放行 appId={} applyNo={}",
                    client.getCompanyUscc(), client.getAppId(), app.getApplyNo());
            return;
        }
        boolean idMatch = reqEmployeeId != null && reqEmployeeId.equals(recEmployeeId);
        boolean codeMatch = reqEmployeeCode != null && reqEmployeeCode.equals(recEmployeeCode);
        if (!idMatch && !codeMatch) {
            throw new ApiException(403, "医生身份与证书申请记录不一致，拒绝吊销（applyNo=" + app.getApplyNo() + "）");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
     * 模板解析：本公司模板优先于全局模板；SUB_CA 模板不允许走 RA。
     */
    private CertTemplate resolveTemplate(String company, String templateCode) {
        List<CertTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<CertTemplate>()
                .eq(CertTemplate::getTemplateCode, templateCode)
                .and(w -> w.isNull(CertTemplate::getCompanyUscc).or().eq(CertTemplate::getCompanyUscc, company))
                .orderByDesc(CertTemplate::getCompanyUscc));
        if (rows.isEmpty()) {
            throw new ApiException(422, "模板不存在或对本公司不可用：" + templateCode);
        }
        CertTemplate tpl = rows.get(0);
        if ("SUB_CA".equals(tpl.getCertType())) {
            throw new ApiException(422, "SUB_CA 证书不走申请流程，请联系 CA 运维");
        }
        return tpl;
    }

    private CertApplication findExternal(String company, String appId, String externalRequestId) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<CertApplication>()
                .eq(CertApplication::getCompanyUscc, company)
                .eq(CertApplication::getExternalSystem, appId)
                .eq(CertApplication::getExternalRequestId, externalRequestId)
                .last("LIMIT 1"));
    }

    private CertApplication requireApplication(ApiClient client, String applyNo) {
        CertApplication app = applicationMapper.selectOne(new LambdaQueryWrapper<CertApplication>()
                .eq(CertApplication::getCompanyUscc, client.getCompanyUscc())
                .eq(CertApplication::getExternalSystem, client.getAppId())
                .eq(CertApplication::getApplyNo, applyNo)
                .last("LIMIT 1"));
        if (app == null) {
            throw new ApiException(404, "申请单不存在（applyNo=" + applyNo + "）");
        }
        return app;
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
}
