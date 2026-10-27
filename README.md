# csssl · CA 根认证中心

为 HIS 生态提供统一信任根与加密传输支撑的独立工程：

- **CA 认证**：根CA初始化（RSA2048 / SM2 国密）、服务签发二级CA（SUB_CA）、证书签发、CSR 签发、吊销 CRL、证书链在线校验、OCSP 三态查询
- **加密传输**：签发证书可直接用于 TLS/mTLS 双向认证；内置 SM2 口令加密（管理后台登录）与 SM4 国密对称加解密工具，支撑 csmig 生产期"SM2签名 + SM4加密报文"改造
- **管理后台**：Vue 3 + Element Plus，与 cslk / csmig 同风格（SM2 口令加密 → BCrypt 存密 → JWT 会话）

## 架构位置

```
┌──────────────┐   TLS / mTLS 双向认证（证书由 csssl 签发）   ┌──────────────────┐
│ cslk HIS      │◄──────────────────────────────────────────►│                  │
│ (院内各微服务) │                                            │  csssl CA认证中心  │
└──────┬───────┘                                            │  (本工程, 8092)   │
       │ HTTP JSON（开发期明文）                               │  根CA/SUB_CA/CRL/ │
       ▼                                                     │  校验/OCSP         │
┌──────────────┐   证书信任链 / SM2签名 / SM4加密（生产期）              └────────▲─────────┘
│ csmig 医保前置 │───────────────────────────────────────────────────────┘
│ (8091 /mip)  │
└──────┬───────┘
       ▼
┌──────────────┐
│ csmis 医保局端│
│ mock (8090)  │
└──────────────┘
```

各子系统接入方式：

| 子系统 | 用途 | 接入方式 |
|---|---|---|
| cslk HIS | 服务端证书 | 签发 SERVER 证书，配置到各微服务 HTTPS/mTLS；`GET /ssl/ca/public/root` 拉取信任根 |
| csmig 前置 | 客户端证书 + 加签密钥 | 签发 CLIENT 证书用于对医保局 mTLS；SM2 私钥对报文摘要签名、SM4 加密报文（对应其生产期 TODO） |
| csmis 模拟 | 开发期证书 | 同 cslk，开发期可复用同一根 |

## 技术栈

- 后端：Spring Boot 3.2.5 / Java 21 / MyBatis-Plus / MySQL（库：`ssl_ca`）
- 证书：BouncyCastle（bcprov + bcpkix），X.509 v3，支持 RSA2048 与 SM2（SM3withSM2 签名）
- 前端：Vue 3 / Vite / Element Plus（管理后台，与 cslk HIS 同风格）
- 鉴权：SM2 口令加密传输 + BCrypt 存密 + JWT 会话（4h/24h 可配，与 cslk/csmig 同方案）

## 目录结构

```
csssl/
├── source/
│   ├── ssl_backend/          # 后端（Spring Boot，端口 8092，context-path /ssl）
│   │   └── src/main/
│   │       ├── java/com/csssl/
│   │       │   ├── controller/    # auth / admin/ca / admin/certs / admin/crl / admin/audit / ca/public(含ocsp)
│   │       │   ├── service/       # CaService CertService CrlService VerifyService OcspService AuthService AuditService
│   │       │   ├── util/          # CertUtil(签发) KeyVault(密钥加密落盘) Sm2Util(口令) Sm4Util(国密对称) PemUtil JwtUtil
│   │       │   ├── entity/ mapper/ dto/ config/ common/
│   │       │   └── CssslApplication.java
│   │       └── resources/
│   │           ├── application.yml
│   │           └── db/schema.sql
│   └── front/                # 管理后台（Vite 端口 5175，代理 /ssl → 8092）
└── README.md
```

## 快速启动

前置：MySQL（`192.168.88.132:3306`，库 `ssl_ca` 自动创建；账号密码可用环境变量覆盖），JDK 21，Node 18+。

```bash
# 后端
cd source/ssl_backend
mvn spring-boot:run        # 启动后监听 http://localhost:8092/ssl

# 前端（管理后台）
cd source/front
npm install
npm run dev                 # http://localhost:5175
npm run build               # 构建产物 dist/
```

首次启动自动完成三件事：

1. 创建默认管理员 `admin / 123456`（登录后请尽快修改）
2. 初始化根CA（RSA2048 默认 / 可配 SM2，有效期 3650 天），私钥 SM4 加密落盘
3. 用根签发服务签发二级CA（SUB_CA，默认 730 天），在线签发/CRL/OCSP 全部由 SUB_CA 承担

> 根私钥经 `SSL_MASTER_KEY`（SM4 主密钥，32位十六进制）加密落盘 `store-dir/root/key.pem.enc`，数据库仅存公钥证书。未配置主密钥时首启生成随机密钥落盘 `store-dir/master.key`（生产必须注入环境变量）。

## 核心 API

### 业务系统公开接口（无需登录）

| 接口 | 说明 |
|---|---|
| `GET /ssl/ca/public/root` | 根证书 PEM 下载（各子系统信任链配置） |
| `GET /ssl/ca/public/subca` | 服务签发二级CA（SUB_CA）证书 PEM（中间CA链配置） |
| `POST /ssl/ca/public/verify` | 证书链在线校验：`{certPem, purpose: TLS/SIGN}` → 叶子→SUB_CA→根 逐级验签/未过期/未吊销/用途匹配 |
| `GET /ssl/ca/public/crl` | CRL 下载（吊销列表，SUB_CA 签名，含 thisUpdate/nextUpdate） |
| `POST /ssl/ca/public/ocsp` | OCSP 查询（`application/ocsp-request` DER → `application/ocsp-response`，三态：good/revoked/unknown） |
| `GET /ssl/ca/public/ocsp/{base64url}` | OCSP 查询 GET 形式（RFC 6960） |

### 管理接口（需 `Authorization: Bearer <token>`）

| 接口 | 说明 |
|---|---|
| `GET /ssl/auth/publicKey` | 登录 SM2 公钥 |
| `POST /ssl/auth/login` | 登录（口令 SM2 密文） |
| `GET /ssl/admin/ca/status` | 根CA状态（含 SUB_CA 信息与根密钥封存状态） |
| `POST /ssl/admin/ca/init` | 初始化/重建根CA（幂等，私钥丢失时吊销旧根重建） |
| `POST /ssl/admin/ca/seal` | 封存根私钥（离线化，此后仅 SUB_CA 在线签发） |
| `POST /ssl/admin/ca/unseal` | 解封根私钥（轮换 SUB_CA 等维护窗口用） |
| `GET /ssl/admin/ca/root/download` | 导出根证书 |
| `GET /ssl/admin/ca/subca/download` | 导出 SUB_CA 证书（中间CA链） |
| `GET /ssl/admin/certs` | 证书列表（类型/状态/关键字筛选 + 分页） |
| `POST /ssl/admin/certs` | 签发证书 `{subjectCn, certType, algorithm, validityDays, san, description}` |
| `POST /ssl/admin/certs/csr` | CSR 签发 `{csrPem, certType, validityDays, san, description}` |
| `POST /ssl/admin/certs/{id}/revoke` | 吊销 `{reason}` |
| `GET /ssl/admin/certs/{id}/cert.pem` | 下载证书（含 SUB_CA 中间CA链） |
| `GET /ssl/admin/certs/{id}/key.pem` | 下载私钥（受开关控制，CSR 签发无私钥） |
| `GET /ssl/admin/crl` | 吊销记录 + CRL PEM + thisUpdate/nextUpdate |
| `GET /ssl/admin/crl/download` | 下载 CRL |
| `GET /ssl/admin/audit` | 审计日志（登录/签发/吊销/封存/导出留痕） |
| `GET /ssl/admin/overview` | 概览统计 |

## 关键设计

- **根CA离线化**：对齐国内持证CA通行架构——根CA仅初始化与签发 SUB_CA，日常签发/CRL/OCSP 全部由 SUB_CA 承担；根私钥支持「封存」离线（`key.pem.enc` 移存为 `.sealed`），封存后根密钥不参与任何在线操作，到期前 `renewBeforeDays` 天自动轮换
- **私钥加密落盘**：所有私钥经 `KeyVault` 以 SM4 加密后落盘（`*.key.pem.enc`），主密钥由 `SSL_MASTER_KEY` 注入（未配置时首启随机生成落盘 `master.key`）；旧明文密钥自动迁移加密；HSM/加密机（GM/T 0018）可在同类接口处替换
- **吊销可发现性**：签发的证书带 CRL 分发点（CDP）与 OCSP 访问点（AIA）扩展；CRL 由 SUB_CA 签名并携带 thisUpdate/nextUpdate；提供标准 OCSP 三态 responder（good/revoked/unknown，RFC 6960，POST+GET 双通道）
- **证书链下载**：`/admin/certs/{id}/cert.pem` 返回叶子 + SUB_CA 完整链，TLS 服务端部署免手工拼接
- **国密支持**：签发算法可选 `SM2`（SM3withSM2 签名、sm2p256v1 曲线），与 openssl / 国密工具链互通；`Sm4Util` 提供 SM4 加解密供 csmig 生产期使用
- **审计留痕**：登录/签发/CSR签发/吊销/封存解封/下载全部落 `ssl_audit_log`，等保审计用
- **鉴权对齐**：登录链路（SM2 加密口令 → BCrypt 校验 → JWT）与 cslk HIS、csmig 完全一致，前端复用 `sm-crypto`

## 生产期配置建议

| 环境变量 | 说明 |
|---|---|
| `SSL_MASTER_KEY` | 私钥加密主密钥（32位十六进制），**生产必须注入**，多实例共享 |
| `SSL_PUBLIC_BASE_URL` | 对外可访问地址（生成证书 CDP/AIA 与 OCSP URL），如 `https://ca.his.local/ssl` |
| `SSL_ROOT_ALGORITHM` | 医保/卫健行业建议 `SM2`（国密） |
| `SSL_SUBCA_VALIDITY_DAYS` / `SSL_SUBCA_RENEW_DAYS` | SUB_CA 有效期与提前轮换天数 |
| `SSL_STORE_DIR` | 共享存储 + 严格文件权限；生产建议将根私钥移入 HSM/加密机（GM/T 0018） |
| `SSL_ALLOW_KEY_DOWNLOAD` | 生产设为 `false` |
| `SSL_SM2_PRIVATE_KEY` / `SSL_SM2_PUBLIC_KEY` | 登录加密 SM2 密钥对，多实例必须显式配置同一对 |
| `SSL_JWT_SECRET` | 生产必须用环境变量覆盖 |
| 数据库账号 | `ssl_ca` 库最小权限，勿用管理员账号 |

## 合规提示（已核验法条）

- 《电子签名法》第17/18条：面向公众发证须工信部许可——csssl 仅限院内自用，**不可对外商用发证**
- 《密码法》第27条 + 《商用密码管理条例》第38/39条：重要系统须使用商用密码并开展密评；若 csssl 服务的系统参与密评，建议根私钥接入加密机并保持根CA离线

## TODO（P1/P2/P3 演进）

- [ ] 双证书体系 + 密钥管理中心（KMC，签名证书自持 + 加密证书托管，GM/T 0034）
- [ ] 时间戳服务（TSA，医疗签名"权威时间源+签名同时打戳"）
- [ ] CRL 增量/分块发布
- [ ] RA/CA 逻辑分离与双人审批、三权分立
- [ ] SCEP/CMP 标准协议接入
- [ ] PKCS#12（.p12）打包下载与证书到期自动预警
- [ ] 管理后台口令修改页
