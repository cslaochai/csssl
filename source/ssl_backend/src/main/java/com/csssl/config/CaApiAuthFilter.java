package com.csssl.config;

import com.csssl.common.ApiException;
import com.csssl.common.Result;
import com.csssl.entity.ApiClient;
import com.csssl.service.ApiClientService;
import com.csssl.util.ApiBodyCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部系统 API 鉴权过滤器（仅 /ca/api/**）：
 * 校验 X-App-Id / X-Timestamp / X-Nonce / X-Signature（HMAC-SHA256），并完成
 * IP 白名单、可选 mTLS 证书指纹、防重放与限流。通过后把 {@link ApiClient} 放进请求属性，
 * 控制器一律以它绑定的 tenantUscc 为数据域——请求 URL 不携带租户标识，租户不可由调用方指定。
 *
 * <p>canonical（六行，LF 分隔，末尾不加换行）：
 * appId \\n timestamp \\n nonce \\n METHOD \\n pathWithQuery \\n sha256Hex(body)；
 * pathWithQuery 含 context-path（如 /ssl/ca/api/v1/applications），GET 请求 body 视为空串。</p>
 *
 * <p>业务报文整包加密：写请求（POST/PUT/PATCH）的非空请求体必须是
 * {"encData":"base64(IV||密文||tag)"} 信封，验签通过后在此解密（AES-256-GCM，
 * 密钥由客户端 secret 派生，见 {@link ApiBodyCrypto}），控制器拿到的是明文 JSON。
 * 签名按实际发送的信封计算；幂等指纹改用解密后的明文哈希，避免同体因 IV 不同被误判为冲突。</p>
 *
 * <p>失败响应沿用统一 Result JSON；认证失败统一 401，不区分 appId 不存在/停用，避免枚举。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class CaApiAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_CLIENT = "caApiClient";
    /**
     * 请求体 SHA-256（hex）：与签名 canonical 末行同值，供提交接口做幂等指纹，避免重复读体再算
     */
    public static final String ATTR_BODY_SHA256 = "caApiBodySha256";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEADER_APP_ID = "X-App-Id";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_NONCE = "X-Nonce";
    private static final String HEADER_SIGNATURE = "X-Signature";

    private final ApiClientService clientService;
    private final CssslProperties props;

    /**
     * nonce → 过期时间（毫秒）
     */
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    /**
     * appId → 分钟窗口计数
     */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private static String header(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String pathWithQuery(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return query == null || query.isBlank() ? uri : uri + "?" + query;
    }

    private static void verifyClientCert(ApiClient client, HttpServletRequest request) {
        String expected = client.getClientCertSha256();
        if (expected == null || expected.isBlank()) {
            return;
        }
        Object attr = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attr instanceof X509Certificate[] certs) || certs.length == 0) {
            throw new ApiException(403, "缺少客户端证书");
        }
        String actual;
        try {
            actual = sha256Hex(certs[0].getEncoded());
        } catch (Exception e) {
            throw new ApiException(403, "客户端证书无法读取");
        }
        if (!normalizeFingerprint(expected).equalsIgnoreCase(actual)) {
            throw new ApiException(403, "客户端证书指纹不匹配");
        }
    }

    private static String normalizeFingerprint(String fp) {
        return fp.replace(":", "").replace(" ", "").trim();
    }

    /**
     * IP 白名单：逗号分隔，支持精确 IP 与 IPv4 CIDR；空白=不限来源。
     */
    static boolean ipAllowed(String allowlist, String ip) {
        if (allowlist == null || allowlist.isBlank() || ip == null || ip.isBlank()) {
            return true;
        }
        for (String raw : allowlist.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (item.contains("/")) {
                if (cidrContains(item, ip)) {
                    return true;
                }
            } else if (item.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cidrContains(String cidr, String ip) {
        try {
            String[] parts = cidr.split("/", 2);
            InetAddress network = InetAddress.getByName(parts[0]);
            InetAddress address = InetAddress.getByName(ip);
            byte[] net = network.getAddress();
            byte[] addr = address.getAddress();
            if (net.length != 4 || addr.length != 4) {
                return false;
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            int n = toInt(net);
            int a = toInt(addr);
            int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
            return (n & mask) == (a & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private static int toInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static String remoteIp(HttpServletRequest request) {
        // 安全口径：白名单校验只看 TCP 对端地址，X-Forwarded-For 可被伪造，不参与判定
        return request.getRemoteAddr();
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hmacHex(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0x0F];
            out[i * 2 + 1] = digits[bytes[i] & 0x0F];
        }
        return new String(out);
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (status == 429) {
            long wait = 60 - (System.currentTimeMillis() / 1000L) % 60;
            response.setHeader("Retry-After", String.valueOf(wait));
        }
        response.getWriter().write(MAPPER.writeValueAsString(Result.error(status, message)));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/ca/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!props.getApi().isEnabled()) {
                throw new ApiException(503, "对外 API 暂未开放");
            }
            String appId = header(request, HEADER_APP_ID);
            String tsRaw = header(request, HEADER_TIMESTAMP);
            String nonce = header(request, HEADER_NONCE);
            String signature = header(request, HEADER_SIGNATURE);
            if (appId == null || tsRaw == null || nonce == null || signature == null) {
                throw new ApiException(401, "认证失败：缺少签名请求头");
            }

            ApiClient client = clientService.requireEnabled(appId);
            if (!ipAllowed(client.getIpAllowlist(), remoteIp(request))) {
                throw new ApiException(403, "来源地址不在白名单内");
            }
            verifyClientCert(client, request);

            long ts;
            try {
                ts = Long.parseLong(tsRaw);
            } catch (NumberFormatException e) {
                throw new ApiException(401, "认证失败：时间戳格式无效");
            }
            long skewMs = props.getApi().getClockSkewSeconds() * 1000L;
            if (Math.abs(Instant.now().toEpochMilli() - ts) > skewMs) {
                throw new ApiException(401, "认证失败：时间戳超出允许偏差");
            }

            byte[] body = readBody(request);
            String bodyHash = sha256Hex(body);
            String canonical = appId + "\n" + tsRaw + "\n" + nonce + "\n"
                    + request.getMethod().toUpperCase() + "\n" + pathWithQuery(request) + "\n"
                    + bodyHash;
            String secret = clientService.secretOf(client);
            String expected = hmacHex(secret, canonical);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
                log.debug("[对外API] 验签失败 appId={} method={} uri={} canonical={}",
                        appId, request.getMethod(), pathWithQuery(request), canonical.replace("\n", "\\n"));
                throw new ApiException(401, "认证失败：签名无效");
            }

            checkNonce(appId, nonce);
            checkRateLimit(appId);
            clientService.touch(client);

            byte[] plainBody = decryptIfNeeded(request, body, secret);
            request.setAttribute(ATTR_CLIENT, client);
            // 幂等指纹取明文：同一业务请求重加密会得到不同密文，按信封哈希会误判为幂等冲突
            request.setAttribute(ATTR_BODY_SHA256, sha256Hex(plainBody));
            chain.doFilter(new CachedBodyRequest(request, plainBody), response);
        } catch (ApiException e) {
            writeError(response, e.getStatus(), e.getMessage());
        }
    }

    /**
     * 写请求的非空请求体必须是加密信封，解密后交给控制器；GET/DELETE 或空体原样放行。
     */
    private byte[] decryptIfNeeded(HttpServletRequest request, byte[] body, String secret) {
        boolean write = switch (request.getMethod().toUpperCase()) {
            case "POST", "PUT", "PATCH" -> true;
            default -> false;
        };
        if (!write || body.length == 0) {
            return body;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new ApiException(400, "请求体必须是加密信封 JSON：{\"encData\":\"...\"}");
        }
        JsonNode enc = node == null ? null : node.get("encData");
        if (enc == null || !enc.isTextual() || enc.asText().isBlank()) {
            throw new ApiException(400, "请求体必须加密：缺少 encData");
        }
        try {
            return ApiBodyCrypto.decrypt(secret, enc.asText()).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "请求体解密失败");
        }
    }

    /**
     * 读取请求体（上限保护）；读出的字节交给包装请求，后续控制器仍可正常读取。
     */
    private byte[] readBody(HttpServletRequest request) throws IOException {
        int max = props.getApi().getMaxBodyBytes();
        try (InputStream in = request.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) {
                    throw new ApiException(413, "请求体超过上限（" + max + " 字节）");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void checkNonce(String appId, String nonce) {
        long now = System.currentTimeMillis();
        long ttl = props.getApi().getNonceTtlSeconds() * 1000L;
        String key = appId + "|" + nonce;
        if (nonces.size() > 20000) {
            nonces.entrySet().removeIf(e -> e.getValue() <= now);
        }
        Long prev = nonces.putIfAbsent(key, now + ttl);
        if (prev != null) {
            if (prev > now) {
                throw new ApiException(401, "认证失败：nonce 在有效期内重复");
            }
            nonces.put(key, now + ttl);
        }
    }

    private void checkRateLimit(String appId) {
        int limit = props.getApi().getRateLimitPerMinute();
        if (limit <= 0) {
            return;
        }
        long minute = System.currentTimeMillis() / 60000L;
        Window w = windows.computeIfAbsent(appId, k -> new Window());
        synchronized (w) {
            if (w.minute != minute) {
                w.minute = minute;
                w.count = 0;
            }
            if (++w.count > limit) {
                throw new ApiException(429, "请求过于频繁，请按 Retry-After 退避");
            }
        }
    }

    private static final class Window {
        private long minute;
        private int count;
    }

    /**
     * 请求体缓存包装：过滤器已读取 body 后，控制器仍可正常 getInputStream/getReader。
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
