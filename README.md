# csssl · CA 根认证中心

为 HIS 生态提供统一信任根与加密传输支撑的独立工程：

- **CA 认证**：根CA初始化（RSA2048 / SM2 国密）、服务端/客户端/二级CA证书签发、CSR 签发、吊销 CRL、证书链在线校验
- **加密传输**：签发证书可直接用于 TLS/mTLS 双向认证；内置 SM2 口令加密（管理后台登录）与 SM4 国密对称加解密工具，支撑 csmig 生产期"SM2签名 + SM4加密报文"改造
- **管理后台**：Vue 3 + Element Plus，与 cslk / csmig 同风格（SM2 口令加密 → BCrypt 存密 → JWT 会话）

## 架构位置

```
┌──────────────┐   TLS / mTLS 双向认证（证书由 csssl 签发）   ┌──────────────────┐
│ cslk HIS      │◄──────────────────────────────────────────►│                  │
│ (院内各微服务) │                                            │  csssl CA认证中心  │
└──────┬───────┘                                            │  (本工程, 8092)   │
       │ HTTP JSON（开发期明文）                               │  根CA/签发/CRL/校验 │
       ▼                                                     └────────▲─────────┘
┌──────────────┐   证书信任链 / SM2签名 / SM4加密（生产期）              │
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
│   │       │   ├── controller/    # auth / admin/ca / admin/certs / admin/crl / admin/audit / ca/public
│   │       │   ├── service/       # CaService CertService CrlService VerifyService AuthService AuditService
│   │       │   ├── util/          # CertUtil(签发) Sm2Util(口令) Sm4Util(国密对称) PemUtil JwtUtil
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

首次启动自动完成两件事：

1. 创建默认管理员 `admin / 123456`（登录后请尽快修改）
2. 初始化根CA（`store-dir/root/` 生成 `cert.pem` + `key.pem`，默认 RSA2048，有效期 3650 天）

> 根CA密钥只落盘（`./data/certs/root/key.pem`），数据库仅存公钥证书。多实例部署请把 `store-dir` 放到共享存储并限制文件权限。

## 核心 API

### 业务系统公开接口（无需登录）

| 接口 | 说明 |
|---|---|
| `GET /ssl/ca/public/root` | 根证书 PEM 下载（各子系统信任链配置） |
| `POST /ssl/ca/public/verify` | 证书链在线校验：`{certPem, purpose: TLS/SIGN}` → 链可信/未过期/未吊销/用途匹配 |
| `GET /ssl/ca/public/crl` | CRL 下载（吊销列表） |

### 管理接口（需 `Authorization: Bearer <token>`）

| 接口 | 说明 |
|---|---|
| `GET /ssl/auth/publicKey` | 登录 SM2 公钥 |
| `POST /ssl/auth/login` | 登录（口令 SM2 密文） |
| `GET /ssl/admin/ca/status` | 根CA状态 |
| `POST /ssl/admin/ca/init` | 初始化/重建根CA（幂等，私钥丢失时吊销旧根重建） |
| `GET /ssl/admin/ca/root/download` | 导出根证书 |
| `GET /ssl/admin/certs` | 证书列表（类型/状态/关键字筛选 + 分页） |
| `POST /ssl/admin/certs` | 签发证书 `{subjectCn, certType, algorithm, validityDays, san, description}` |
| `POST /ssl/admin/certs/csr` | CSR 签发 `{csrPem, certType, validityDays, san, description}` |
| `POST /ssl/admin/certs/{id}/revoke` | 吊销 `{reason}` |
| `GET /ssl/admin/certs/{id}/cert.pem` | 下载证书 |
| `GET /ssl/admin/certs/{id}/key.pem` | 下载私钥（受开关控制，CSR 签发无私钥） |
| `GET /ssl/admin/crl` | 吊销记录 + CRL PEM |
| `GET /ssl/admin/crl/download` | 下载 CRL |
| `GET /ssl/admin/audit` | 审计日志（登录/签发/吊销/导出留痕） |
| `GET /ssl/admin/overview` | 概览统计 |

## 关键设计

- **私钥安全**：所有私钥只落盘不落库；根CA私钥禁止在线下载（`/admin/certs/{id}/key.pem` 对 ROOT 拒绝）；CSR 签发场景私钥由申请方自持，系统不产生私钥
- **国密支持**：签发算法可选 `SM2`（SM3withSM2 签名、sm2p256v1 曲线），与 openssl / 国密工具链互通；`Sm4Util` 提供 SM4 加解密供 csmig 生产期使用
- **吊销闭环**：吊销即时写状态并进入 CRL（根CA签名、30天更新、支持吊销原因码），业务系统可定期拉取 `/ca/public/crl`
- **审计留痕**：登录/签发/CSR签发/吊销/下载全部落 `ssl_audit_log`，等保审计用
- **鉴权对齐**：登录链路（SM2 加密口令 → BCrypt 校验 → JWT）与 cslk HIS、csmig 完全一致，前端复用 `sm-crypto`

## 生产期配置建议

| 项 | 说明 |
|---|---|
| `SSL_ROOT_ALGORITHM` | 医保/卫健行业建议 `SM2`（国密） |
| `SSL_STORE_DIR` | 共享存储 + 严格文件权限，最好接 HSM/加密机 |
| `SSL_ALLOW_KEY_DOWNLOAD` | 生产设为 `false` |
| `SSL_SM2_PRIVATE_KEY` / `SSL_SM2_PUBLIC_KEY` | 登录加密 SM2 密钥对，多实例必须显式配置同一对 |
| `SSL_JWT_SECRET` | 生产必须用环境变量覆盖 |
| 数据库账号 | `ssl_ca` 库最小权限，勿用管理员账号 |

## TODO

- [ ] 二级CA（SUB_CA）级联签发与跨 CA 链校验
- [ ] PKCS#12（.p12）打包下载与证书轮换提醒
- [ ] OCSP 在线状态协议（替代/补充 CRL）
- [ ] 证书到期自动预警（对接告警通道）
- [ ] 管理后台口令修改页
