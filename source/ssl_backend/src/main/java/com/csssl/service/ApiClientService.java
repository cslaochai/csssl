package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.ApiException;
import com.csssl.entity.ApiClient;
import com.csssl.entity.CaTenant;
import com.csssl.mapper.ApiClientMapper;
import com.csssl.util.KeyVault;
import com.csssl.vo.ApiClientSecretVO;
import com.csssl.vo.ApiClientVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部系统 API 客户端登记与凭证管理。
 *
 * <p>凭证模型：appId 为随机不可猜标识（app_+32hex），secret 为随机密钥（sk_+64hex），
 * secret 经密钥保险库 SM4 加密后落库 ssl_key_vault（rel_dir=api-clients, name=appId），
 * ssl_api_client 表本身不保存明文或密文；secret 只在登记/轮换响应中展示一次。</p>
 *
 * <p>租户绑定：客户端归属租户在登记时确定，运行期请求 URL 不携带租户标识，
 * 服务端一律以 appId 反查的 tenantKey 作为数据域（不信任调用方任何租户参数）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiClientService {

    /**
     * 建租户时自动登记的默认客户端名称（一个租户默认一份接入凭证）
     */
    public static final String DEFAULT_CLIENT_NAME = "默认接入客户端";
    private static final String VAULT_DIR = "api-clients";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ApiClientMapper clientMapper;
    private final TenantService tenantService;
    private final KeyVault keyVault;
    private final AuditService auditService;

    private static String generateSecret() {
        return "sk_" + randomHex(32);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        char[] out = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            out[i * 2] = HEX[(buf[i] >> 4) & 0x0F];
            out[i * 2 + 1] = HEX[buf[i] & 0x0F];
        }
        return new String(out);
    }

    /**
     * 登记 API 客户端（仅全局系统管理员，控制器已校验）：返回一次性 secret。
     */
    public ApiClientSecretVO create(String tenantKey, String clientName, String ipAllowlist,
                                    String clientCertSha256, String operator, String ip) {
        if (tenantKey == null || tenantKey.isBlank()) {
            throw new IllegalArgumentException("必须指定客户端绑定的租户");
        }
        String tenant = tenantKey.trim();
        CaTenant tenantRow = tenantService.get(tenant);
        if (!TenantService.STATUS_ACTIVE.equals(tenantRow.getStatus())) {
            throw new IllegalArgumentException("租户状态不可用: " + tenant + "（" + tenantRow.getStatus() + "）");
        }
        String name = clientName == null || clientName.isBlank() ? "外部系统客户端" : clientName.trim();
        if (name.length() > 64) {
            throw new IllegalArgumentException("客户端名称不超过64字符");
        }
        String appId = generateUniqueAppId();
        String secret = generateSecret();

        ApiClient client = new ApiClient();
        client.setAppId(appId);
        client.setTenantKey(tenant);
        client.setClientName(name);
        client.setIpAllowlist(blankToNull(ipAllowlist));
        client.setClientCertSha256(blankToNull(clientCertSha256));
        client.setSecretVersion(1);
        client.setStatus(1);
        client.setCreatedBy(operator);
        client.setCreatedAt(LocalDateTime.now());
        clientMapper.insert(client);
        keyVault.storeKey(VAULT_DIR, appId, secret);

        auditService.record(operator, "API_CLIENT_CREATE", appId,
                "登记外部API客户端：" + name + "（appId=" + appId + "，租户=" + tenant
                        + (client.getIpAllowlist() == null ? "" : "，IP白名单=" + client.getIpAllowlist()) + "）",
                ip, tenant);
        log.info("[API客户端] 已登记 appId={}, tenant={}, name={}", appId, tenant, name);
        return secretVo(client, tenantRow.getTenantName(), secret);
    }

    /**
     * 轮换 secret：旧 secret 立即失效（secret_version+1）。
     */
    public ApiClientSecretVO rotate(Long id, String operator, String ip) {
        ApiClient client = require(id);
        String secret = generateSecret();
        client.setSecretVersion((client.getSecretVersion() == null ? 1 : client.getSecretVersion()) + 1);
        client.setRotatedAt(LocalDateTime.now());
        clientMapper.updateById(client);
        keyVault.storeKey(VAULT_DIR, client.getAppId(), secret);

        auditService.record(operator, "API_CLIENT_ROTATE", client.getAppId(),
                "轮换外部API客户端密钥：" + client.getClientName() + "（新版本 " + client.getSecretVersion() + "）",
                ip, client.getTenantKey());
        log.info("[API客户端] secret 已轮换 appId={}, version={}", client.getAppId(), client.getSecretVersion());
        return secretVo(client, tenantName(client.getTenantKey()), secret);
    }

    /**
     * 启停客户端（停用立即拒绝其请求）。
     */
    public ApiClientVO setStatus(Long id, boolean enabled, String operator, String ip) {
        ApiClient client = require(id);
        client.setStatus(enabled ? 1 : 0);
        clientMapper.updateById(client);
        auditService.record(operator, enabled ? "API_CLIENT_ENABLE" : "API_CLIENT_DISABLE", client.getAppId(),
                (enabled ? "启用" : "停用") + "外部API客户端：" + client.getClientName(),
                ip, client.getTenantKey());
        ApiClientVO vo = ApiClientVO.from(client);
        vo.setTenantName(tenantName(client.getTenantKey()));
        return vo;
    }

    /**
     * 管理端列表（不返回 secret）。
     */
    public List<ApiClientVO> list() {
        List<ApiClient> clients = clientMapper.selectList(new LambdaQueryWrapper<ApiClient>()
                .orderByDesc(ApiClient::getCreatedAt));
        List<ApiClientVO> rows = new ArrayList<>(clients.size());
        for (ApiClient c : clients) {
            ApiClientVO vo = ApiClientVO.from(c);
            vo.setTenantName(tenantName(c.getTenantKey()));
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 租户默认接入客户端（无则 null；租户页展示 App ID 与重置密钥用）
     */
    public ApiClient findFirstByTenant(String tenantKey) {
        return clientMapper.selectOne(new LambdaQueryWrapper<ApiClient>()
                .eq(ApiClient::getTenantKey, tenantKey)
                .orderByAsc(ApiClient::getId)
                .last("LIMIT 1"));
    }

    /**
     * 生成/重置租户默认接入凭证：已有则轮换，没有则新登记；secret 只在本次响应返回。
     */
    public ApiClientSecretVO resetForTenant(String tenantKey, String operator, String ip) {
        ApiClient existing = findFirstByTenant(tenantKey);
        if (existing == null) {
            return create(tenantKey, DEFAULT_CLIENT_NAME, null, null, operator, ip);
        }
        return rotate(existing.getId(), operator, ip);
    }

    /**
     * 认证用：按 appId 取启用客户端（不存在/停用一律 401，不泄露存在性）。
     */
    public ApiClient requireEnabled(String appId) {
        ApiClient client = clientMapper.selectOne(new LambdaQueryWrapper<ApiClient>()
                .eq(ApiClient::getAppId, appId));
        if (client == null || client.getStatus() == null || client.getStatus() != 1) {
            throw new ApiException(401, "认证失败：客户端凭证无效或已停用");
        }
        if (!CaTenant.LEGACY_TENANT_KEY.equals(client.getTenantKey())) {
            CaTenant tenant = tenantService.findByKey(client.getTenantKey());
            if (tenant == null || !TenantService.STATUS_ACTIVE.equals(tenant.getStatus())) {
                throw new ApiException(403, "租户不可用，请联系 CA 运维");
            }
        }
        return client;
    }

    /**
     * 读取客户端 secret 明文（仅内存使用，用于校验签名）。
     */
    public String secretOf(ApiClient client) {
        String secret = keyVault.loadKey(VAULT_DIR, client.getAppId());
        if (secret == null || secret.isBlank()) {
            throw new ApiException(503, "客户端密钥不可用，请联系 CA 运维重新轮换");
        }
        return secret;
    }

    /**
     * 记录最近使用时间（节流：10 分钟内不重复写库）。
     */
    public void touch(ApiClient client) {
        LocalDateTime now = LocalDateTime.now();
        if (client.getLastUsedAt() != null && client.getLastUsedAt().isAfter(now.minusMinutes(10))) {
            return;
        }
        ApiClient patch = new ApiClient();
        patch.setId(client.getId());
        patch.setLastUsedAt(now);
        clientMapper.updateById(patch);
        client.setLastUsedAt(now);
    }

    public String tenantName(String tenantKey) {
        CaTenant tenant = tenantService.findByKey(tenantKey);
        return tenant == null ? null : tenant.getTenantName();
    }

    private ApiClient require(Long id) {
        ApiClient client = clientMapper.selectById(id);
        if (client == null) {
            throw new IllegalArgumentException("API客户端不存在（id=" + id + "）");
        }
        return client;
    }

    private ApiClientSecretVO secretVo(ApiClient client, String tenantName, String secret) {
        ApiClientSecretVO vo = new ApiClientSecretVO();
        vo.setId(client.getId());
        vo.setAppId(client.getAppId());
        vo.setTenantKey(client.getTenantKey());
        vo.setTenantName(tenantName);
        vo.setClientName(client.getClientName());
        vo.setSecret(secret);
        vo.setSecretVersion(client.getSecretVersion());
        return vo;
    }

    private String generateUniqueAppId() {
        for (int i = 0; i < 5; i++) {
            String appId = "app_" + randomHex(16);
            if (clientMapper.selectCount(new LambdaQueryWrapper<ApiClient>().eq(ApiClient::getAppId, appId)) == 0) {
                return appId;
            }
        }
        throw new IllegalStateException("生成客户端标识失败，请重试");
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
