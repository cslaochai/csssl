package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.common.ApiException;
import com.csssl.entity.ApiClient;
import com.csssl.entity.CaCompany;
import com.csssl.entity.CaSystem;
import com.csssl.entity.LoginUser;
import com.csssl.enums.CompanyStatusEnum;
import com.csssl.mapper.ApiClientMapper;
import com.csssl.config.CompanyVaultResolver;
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
 * <p>归属模型：客户端挂在公司下的某个「系统」（system_name，即主体 O）上，一个系统一套共享密钥；
 * 但密钥的物理存储仍按公司域 vault 定位，避免改动已验证的 HIS 验签解密链路。
 * 运行期请求 URL 不携带公司/系统标识，服务端一律以 appId 反查（不信任调用方任何参数）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiClientService {

    /**
     * 无具体系统上下文时使用的兜底客户端名称（存量公司重置凭证路径）
     */
    public static final String DEFAULT_CLIENT_NAME = "默认接入客户端";
    private static final String VAULT_DIR = "api-clients";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ApiClientMapper clientMapper;
    private final CompanyService companyService;
    private final CaSystemService caSystemService;
    private final CompanyVaultResolver companyVaultResolver;
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
     * 登记 API 客户端（仅接入凭证管理员，控制器已校验）：必须指定已登记的系统，返回一次性 secret。
     */
    public ApiClientSecretVO create(String companyUscc, String clientName, String systemName,
                                    String ipAllowlist, String clientCertSha256, LoginUser actor, String ip) {
        if (companyUscc == null || companyUscc.isBlank()) {
            throw new IllegalArgumentException("必须指定客户端绑定的公司");
        }
        String company = companyUscc.trim();
        CaCompany companyRow = companyService.get(company);
        if (!CompanyStatusEnum.ACTIVE.is(companyRow.getStatus())) {
            throw new IllegalArgumentException("公司状态不可用: " + company + "（" + companyRow.getStatus() + "）");
        }
        CaSystem system = caSystemService.requireByName(company, systemName);
        String name = clientName == null || clientName.isBlank()
                ? system.getSystemName() + "接入客户端" : clientName.trim();
        if (name.length() > 64) {
            throw new IllegalArgumentException("客户端名称不超过64字符");
        }
        String appId = generateUniqueAppId();
        String secret = generateSecret();

        ApiClient client = new ApiClient();
        client.setAppId(appId);
        client.setCompanyUscc(company);
        client.setClientName(name);
        client.setSystemName(system.getSystemName());
        client.setIpAllowlist(blankToNull(ipAllowlist));
        client.setClientCertSha256(blankToNull(clientCertSha256));
        client.setSecretVersion(1);
        client.setStatus(1);
        client.setCreatedAt(LocalDateTime.now());
        clientMapper.insert(client);
        companyVaultResolver.forCompany(company).storeKey(VAULT_DIR, appId, secret);

        auditService.record(actor, "API_CLIENT_CREATE", appId,
                "登记外部API客户端：" + name + "（appId=" + appId + "，公司=" + company
                        + "，系统=" + system.getSystemName()
                        + (client.getIpAllowlist() == null ? "" : "，IP白名单=" + client.getIpAllowlist()) + "）",
                ip, company);
        log.info("[API客户端] 已登记 appId={}, company={}, system={}, name={}",
                appId, company, system.getSystemName(), name);
        return secretVo(client, companyRow.getCompanyName(), secret);
    }

    /**
     * 轮换 secret：旧 secret 立即失效（secret_version+1）。
     */
    public ApiClientSecretVO rotate(Long id, LoginUser actor, String ip) {
        ApiClient client = require(id);
        String secret = generateSecret();
        client.setSecretVersion((client.getSecretVersion() == null ? 1 : client.getSecretVersion()) + 1);
        client.setRotatedAt(LocalDateTime.now());
        clientMapper.updateById(client);
        companyVaultResolver.forCompany(client.getCompanyUscc()).storeKey(VAULT_DIR, client.getAppId(), secret);

        auditService.record(actor, "API_CLIENT_ROTATE", client.getAppId(),
                "轮换外部API客户端密钥：" + client.getClientName() + "（新版本 " + client.getSecretVersion() + "）",
                ip, client.getCompanyUscc());
        log.info("[API客户端] secret 已轮换 appId={}, version={}", client.getAppId(), client.getSecretVersion());
        return secretVo(client, companyName(client.getCompanyUscc()), secret);
    }

    /**
     * 启停客户端（停用立即拒绝其请求）。
     */
    public ApiClientVO setStatus(Long id, boolean enabled, LoginUser actor, String ip) {
        ApiClient client = require(id);
        client.setStatus(enabled ? 1 : 0);
        clientMapper.updateById(client);
        auditService.record(actor, enabled ? "API_CLIENT_ENABLE" : "API_CLIENT_DISABLE", client.getAppId(),
                (enabled ? "启用" : "停用") + "外部API客户端：" + client.getClientName(),
                ip, client.getCompanyUscc());
        ApiClientVO vo = ApiClientVO.from(client);
        vo.setCompanyName(companyName(client.getCompanyUscc()));
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
            vo.setCompanyName(companyName(c.getCompanyUscc()));
            rows.add(vo);
        }
        return rows;
    }

    /**
     * 公司首个接入客户端（无则 null；存量公司重置凭证用）
     */
    public ApiClient findFirstByCompany(String companyUscc) {
        return clientMapper.selectOne(new LambdaQueryWrapper<ApiClient>()
                .eq(ApiClient::getCompanyUscc, companyUscc)
                .orderByAsc(ApiClient::getId)
                .last("LIMIT 1"));
    }

    /**
     * 生成/重置公司接入凭证：已有则轮换；没有则挂到该公司首个系统下新登记；
     * 连系统都没有时（存量公司）按公司主体 O 自动补建首个系统。
     */
    public ApiClientSecretVO resetForCompany(String companyUscc, LoginUser actor, String ip) {
        ApiClient existing = findFirstByCompany(companyUscc);
        if (existing == null) {
            CaSystem system = caSystemService.firstOrCreateForCompany(companyUscc, actor, ip);
            return create(companyUscc, DEFAULT_CLIENT_NAME, system.getSystemName(),
                    null, null, actor, ip);
        }
        return rotate(existing.getId(), actor, ip);
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
        if (!CaCompany.LEGACY_NAMESPACE.equals(client.getCompanyUscc())) {
            CaCompany company = companyService.findByUscc(client.getCompanyUscc());
            if (company == null || !CompanyStatusEnum.ACTIVE.is(company.getStatus())) {
                throw new ApiException(403, "公司不可用，请联系 CA 运维");
            }
        }
        return client;
    }

    /**
     * 读取客户端 secret 明文（仅内存使用，用于校验签名）。
     */
    public String secretOf(ApiClient client) {
        String secret = companyVaultResolver.forCompany(client.getCompanyUscc()).loadKey(VAULT_DIR, client.getAppId());
        if (secret == null || secret.isBlank()) {
            throw new ApiException(503, "客户端密钥不可用，请联系 CA 运维重新轮换");
        }
        return secret;
    }

    /**
     * 按 appId 读取 secret 明文（仅公司变更单一次性回执使用，调用方负责领取标记与审计）。
     */
    public String secretOfAppId(String appId) {
        ApiClient client = clientMapper.selectOne(new LambdaQueryWrapper<ApiClient>()
                .eq(ApiClient::getAppId, appId));
        if (client == null) {
            throw new IllegalStateException("接入客户端不存在（appId=" + appId + "）");
        }
        String secret = companyVaultResolver.forCompany(client.getCompanyUscc()).loadKey(VAULT_DIR, appId);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("接入密钥不可用，请重新发起重置变更单");
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

    public String companyName(String companyUscc) {
        CaCompany company = companyService.findByUscc(companyUscc);
        return company == null ? null : company.getCompanyName();
    }

    private ApiClient require(Long id) {
        ApiClient client = clientMapper.selectById(id);
        if (client == null) {
            throw new IllegalArgumentException("API客户端不存在（id=" + id + "）");
        }
        return client;
    }

    private ApiClientSecretVO secretVo(ApiClient client, String companyName, String secret) {
        ApiClientSecretVO vo = new ApiClientSecretVO();
        vo.setId(client.getId());
        vo.setAppId(client.getAppId());
        vo.setCompanyUscc(client.getCompanyUscc());
        vo.setCompanyName(companyName);
        vo.setClientName(client.getClientName());
        vo.setSystemName(client.getSystemName());
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
