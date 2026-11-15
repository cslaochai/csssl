package com.csssl.service;

import cn.hutool.crypto.asymmetric.SM2;
import com.csssl.config.CssslProperties;
import com.csssl.entity.CaCert;
import com.csssl.entity.CertApplication;
import com.csssl.enums.CertApplicationStatusEnum;
import com.csssl.util.SignUtil;
import com.csssl.util.Sm2KeyTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.util.Base64;
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
 * <p><b>签名</b>：SM2 非对称签名（SM3withSM2）。CA 侧保管推送私钥，HIS 侧只配置推送公钥。
 * 每个推送带 {@code X-Ca-Timestamp} / {@code X-Ca-Nonce} / {@code X-Ca-Signature} / {@code X-Ca-Key-Id}，
 * 签名串为 {@code timestamp \n nonce \n sha256Hex(body)}，签名值 Base64 编码。
 * 私钥未配置时<b>拒绝启动</b>（不自动生成，避免重启换钥导致 HIS 侧验签全失败）；公钥是公开的，泄露也只能验签不能伪造。</p>
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
    public static final String HEADER_KEY_ID = "X-Ca-Key-Id";

    private final CssslProperties props;
    private final ObjectMapper objectMapper;
    private final CertService certService;

    /** 推送签名 SM2 私钥（hex），启动时初始化 */
    private volatile String pushPrivateKeyHex;
    /** 推送签名 SM2 公钥（hex，04 前缀），启动时初始化 */
    private volatile String pushPublicKeyHex;
    /** 公钥指纹前 16 位，作为 keyId 随推送发出，HIS 侧按 ID 选公钥 */
    private volatile String pushKeyId;

    /**
     * 启动时装载推送签名密钥：<b>私钥必填</b>，缺配置、格式非法、与显式配置的公钥不匹配，一律抛出异常中止启动。
     *
     * <p>这里不做任何自动生成。推送私钥必须跨重启稳定：自动生成时本次启动能正常跑，
     * 下次重启 HIS 侧配的公钥就全部验签失败，故障要等重启后才暴露，比启动即失败难排查得多。
     * 密钥对生成是一次性的运维动作，见 {@link Sm2KeyTool}（命令行工具），生成后固定注入配置。</p>
     */
    @PostConstruct
    public void initPushKey() {
        CssslProperties.HisPush cfg = props.getHisPush();
        if (!cfg.isEnabled()) {
            // 总开关关闭时根本不推送，密钥无意义；此时不校验，避免关了推送还被密钥卡住启动
            log.info("[状态推送] csssl.his-push.enabled=false，推送已关闭，跳过签名密钥校验");
            return;
        }
        String prv = cfg.getSignPrivateKey() == null ? "" : cfg.getSignPrivateKey().trim().toLowerCase();
        if (prv.startsWith("0x")) {
            prv = prv.substring(2);
        }
        if (prv.isEmpty()) {
            throw new IllegalStateException("[状态推送] 缺少推送签名私钥 csssl.his-push.sign-private-key："
                    + "生产用环境变量 SSL_HIS_PUSH_SIGN_KEY 注入，本机开发写在 application-dev.yml。"
                    + "私钥必须固定且跨重启不变，HIS 侧只配对应的公钥；"
                    + "确实不需要推送的环境，请显式设置 SSL_HIS_PUSH_ENABLED=false 关闭总开关。"
                    + "密钥对由 java -cp <classpath> com.csssl.util.Sm2KeyTool 手工生成一次。");
        }
        if (!Sm2KeyTool.isValidPrivateKey(prv)) {
            throw new IllegalStateException("[状态推送] csssl.his-push.sign-private-key 格式非法："
                    + "需要 64 位十六进制 SM2 私钥（且 0 < d < n），当前长度=" + prv.length() + "。");
        }
        String pub = cfg.getSignPublicKey() == null ? "" : cfg.getSignPublicKey().trim().toLowerCase();
        String derived = Sm2KeyTool.derivePublicKey(prv);
        if (!pub.isEmpty()) {
            if (!Sm2KeyTool.isValidPublicKey(pub)) {
                throw new IllegalStateException("[状态推送] csssl.his-push.sign-public-key 格式非法："
                        + "需要 130 位十六进制未压缩公钥（04 前缀），当前长度=" + pub.length() + "。");
            }
            if (!pub.equals(derived)) {
                throw new IllegalStateException("[状态推送] csssl.his-push.sign-public-key 与私钥推导的公钥不是同一对"
                        + "（配置公钥 keyId=" + Sm2KeyTool.keyId(pub)
                        + "，私钥推导 keyId=" + Sm2KeyTool.keyId(derived)
                        + "）：HIS 侧必然验签失败。请把公钥项留空由私钥推导，或核对两端配置。");
            }
        }
        this.pushPrivateKeyHex = prv;
        this.pushPublicKeyHex = pub.isEmpty() ? derived : pub;
        this.pushKeyId = Sm2KeyTool.keyId(this.pushPublicKeyHex);
        log.info("[状态推送] SM2 推送签名密钥就绪（来自配置，非自动生成），keyId={}，公钥={}...",
                pushKeyId, pushPublicKeyHex.substring(0, Math.min(32, pushPublicKeyHex.length())));
    }

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
        if (pushPrivateKeyHex == null || pushPrivateKeyHex.isBlank()) {
            log.warn("[状态推送] 推送签名私钥未就绪，拒绝推送 applyNo={}", app.getApplyNo());
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(payloadOf(app));
            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String canonical = ts + "\n" + nonce + "\n" + SignUtil.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
            // SM2 私钥签名，Base64 输出
            SM2 sm2 = new SM2(pushPrivateKeyHex, pushPublicKeyHex);
            String signature = Base64.getEncoder().encodeToString(
                    sm2.sign(canonical.getBytes(StandardCharsets.UTF_8)));
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
                    .header(HEADER_KEY_ID, pushKeyId)
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
