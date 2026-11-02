package com.csssl.service;

import com.csssl.config.CssslProperties;
import com.csssl.entity.CaCert;
import com.csssl.entity.CertApplication;
import com.csssl.enums.CertApplicationStatusEnum;
import com.csssl.util.SignUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 申请单状态变更推送（CA → 外部系统）。
 *
 * <p>外部系统（HIS）只保留「提交申请」与「吊销证书」两个出站能力，没有查询权限，
 * 所以审核/驳回/签发/撤销必须由 CA 主动推送。只推送 API 渠道（externalSystem 非空）提交的申请单；
 * 推送失败只记日志，不影响 CA 侧已提交的业务动作——外部系统可凭 applyNo 人工核对后重试。</p>
 *
 * <p><b>签名</b>：每个推送都带 {@code X-Ca-Timestamp} / {@code X-Ca-Nonce} / {@code X-Ca-Signature}，
 * 签名串为 {@code timestamp \n nonce \n sha256Hex(body)} 的 HMAC-SHA256（hex）。
 * 密钥未配置时<b>拒绝推送</b>——无签名的推送可被伪造，等于替 HIS 装上攻击者指定的证书。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HisPushService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 推送签名头（HIS 侧按同名头取值验签） */
    public static final String HEADER_TIMESTAMP = "X-Ca-Timestamp";
    public static final String HEADER_NONCE = "X-Ca-Nonce";
    public static final String HEADER_SIGNATURE = "X-Ca-Signature";

    private final CssslProperties props;
    private final ObjectMapper objectMapper;
    private final CertService certService;

    /**
     * 推送一次申请单状态；ISSUED 时随报文带上证书 PEM 与有效期，供外部系统校验公钥后自动回填。
     */
    public void pushApplication(CertApplication app) {
        CssslProperties.HisPush cfg = props.getHisPush();
        if (!cfg.isEnabled() || app == null) {
            return;
        }
        if (app.getApplyNo() == null || app.getApplyNo().isBlank()) {
            return;
        }
        if (app.getExternalSystem() == null || app.getExternalSystem().isBlank()) {
            // 只有外部系统（API 渠道）提交的单才需要回推，院内 Web 流程没有外部接收方
            return;
        }
        String url = cfg.getCallbackUrl();
        if (url == null || url.isBlank()) {
            log.warn("[状态推送] 未配置 csssl.his-push.callback-url，跳过推送 applyNo={}", app.getApplyNo());
            return;
        }
        String secret = cfg.getSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("[状态推送] 未配置 csssl.his-push.secret（推送签名密钥），拒绝推送 applyNo={}；"
                    + "无签名报文可被伪造，需先与 HIS 约定同一密钥", app.getApplyNo());
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(payloadOf(app));
            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = SignUtil.hmacHex(secret,
                    ts + "\n" + nonce + "\n" + SignUtil.sha256Hex(body.getBytes(StandardCharsets.UTF_8)));
            warnIfInsecure(url);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header(HEADER_TIMESTAMP, ts)
                    .header(HEADER_NONCE, nonce)
                    .header(HEADER_SIGNATURE, signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("[状态推送] 失败 applyNo={} status={} {} → {} body={}", app.getApplyNo(),
                        app.getStatus(), url, response.statusCode(), abbreviate(response.body()));
                return;
            }
            log.info("[状态推送] 成功 applyNo={} status={} → {}", app.getApplyNo(), app.getStatus(), url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[状态推送] 被中断 applyNo={}", app.getApplyNo());
        } catch (Exception e) {
            log.warn("[状态推送] 异常 applyNo={} err={}", app.getApplyNo(), e.toString());
        }
    }

    /**
     * 推送报文（字段名与 HIS 侧 SignCaPushDTO 逐字一致；时间统一 yyyy-MM-dd HH:mm:ss）。
     */
    private Map<String, Object> payloadOf(CertApplication app) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString().replace("-", ""));
        payload.put("applyNo", app.getApplyNo());
        payload.put("externalRequestId", app.getExternalRequestId());
        payload.put("status", app.getStatus());
        payload.put("statusText", statusText(app.getStatus()));
        payload.put("reviewedByName", app.getReviewedByName());
        payload.put("reviewComment", app.getReviewComment());
        payload.put("reviewedAt", format(app.getReviewedAt()));
        payload.put("issuedAt", null);
        if (CertApplicationStatusEnum.ISSUED.is(app.getStatus()) && app.getIssuedCertId() != null) {
            CaCert cert = certService.findById(app.getIssuedCertId());
            payload.put("issuedAt", format(cert.getCreatedAt()));
            payload.put("certificate", certificateOf(cert));
        }
        return payload;
    }

    private Map<String, Object> certificateOf(CaCert cert) {
        Map<String, Object> certificate = new LinkedHashMap<>();
        certificate.put("serialHex", cert.getSerialHex());
        certificate.put("subjectDn", cert.getSubjectCn());
        certificate.put("issuerDn", cert.getIssuerCn());
        certificate.put("algorithm", cert.getAlgorithm());
        certificate.put("notBefore", format(cert.getValidFrom()));
        certificate.put("notAfter", format(cert.getValidTo()));
        certificate.put("certPem", cert.getCertPem());
        // 证书链：叶子 + SUB_CA；外部系统只校验公钥并回填，链留档备查
        certificate.put("chainPem", certService.certPem(cert.getId()));
        return certificate;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : TS.format(time);
    }

    private static String statusText(String status) {
        CertApplicationStatusEnum s = CertApplicationStatusEnum.fromCode(status);
        return s == null ? status : s.getLabel();
    }

    /**
     * 回调地址非 https 时告警：报文里带证书 PEM 与主体信息，明文传输等于对外泄露一份资产清单。
     * 本机回环不告警，避免本地联调刷屏。
     */
    private void warnIfInsecure(String url) {
        if (url.startsWith("https://")) {
            return;
        }
        if (url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) {
            return;
        }
        log.warn("[状态推送] 回调地址非 https，报文与证书 PEM 将以明文传输：{}", url);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }
}
