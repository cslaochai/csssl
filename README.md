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
│ (8091 /mig)  │
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

启动时自动完成初始化（**全部幂等：缺了才建，已有即复用，重启绝不覆盖**）：

1. 建库建表：**仅首次手工导入 `db/schema.sql`**（系统启动不执行任何 DDL，不允许重建表结构）
2. 创建默认管理员 `admin / 123456`（已存在则跳过；登录后请尽快修改）
3. 根CA + SUB_CA 及私钥：**仅当无根记录且无私钥文件时才生成**（RSA2048 默认 / 可配 SM2，根有效期 3650 天，SUB_CA 默认 730 天）；已有则直接加载复用，私钥不重写。唯一会重建的场景：根私钥文件丢失（此时按换锁语义吊销旧根与旧 SUB_CA 并重建新链，需管理端主动触发「初始化根CA」）

> 根私钥经 `SSL_MASTER_KEY`（SM4 主密钥，32位十六进制）加密落盘 `store-dir/root/key.pem.enc`，数据库仅存公钥证书。未配置主密钥时首启生成随机密钥落盘 `store-dir/master.key`（生产必须注入环境变量）。

## 核心 API

### 业务系统公开接口（无需登录）

| 接口 | 说明 |
|---|---|
| `GET /ssl/ca/public/root` | 根证书 PEM 下载（各子系统信任链配置） |
| `GET /ssl/ca/public/subca` | 服务签发二级CA（SUB_CA）证书 PEM（中间CA链配置） |
| `POST /ssl/ca/public/verify` | 证书链在线校验：`{certPem, purpose: TLS/SIGN}` → 叶子→SUB_CA→根 逐级验签/未过期/未吊销/用途匹配 |
| `GET /ssl/ca/public/crl` | CRL 下载（吊销列表，SUB_CA 签名，含 thisUpdate/nextUpdate） |
| `GET /ssl/ca/public/crl/delta` | 增量 CRL（dCRL）下载：自最近全量发布以来新增的吊销/挂起条目（RFC 5280 §5.2.4，带 DeltaCRLIndicator） |
| `POST /ssl/ca/public/ocsp` | OCSP 查询（`application/ocsp-request` DER → `application/ocsp-response`，三态：good/revoked/unknown） |
| `GET /ssl/ca/public/ocsp/{base64url}` | OCSP 查询 GET 形式（RFC 6960） |
| `/ssl/ca/public/{orgKey}/…` | 多机构变体：`root`/`subca`/`crl`/`crl/delta`/`ocsp`/`verify` 均带机构路径段，按该机构信任域验链/取吊销（无 orgKey 段=default 域，兼容存量） |

### 管理接口（需 `Authorization: Bearer <token>`）

| 接口 | 说明 |
|---|---|
| `GET /ssl/auth/publicKey` | 登录 SM2 公钥 |
| `POST /ssl/auth/login` | 登录（口令 SM2 密文；5 次失败锁定 15 分钟） |
| `POST /ssl/auth/changePassword` | 自助改口令（SM2 密文新旧口令；改后旧 token 即刻失效） |
| `GET /ssl/admin/ca/status` | 根CA状态（含 SUB_CA 信息与根密钥封存状态；`?orgKey=` 切机构域，default 兼容存量） |
| `GET /ssl/admin/orgs` | 机构注册表总览（各机构根CA状态摘要，多机构域拓扑；仅全局 ADMIN） |
| `POST /ssl/admin/orgs` | 开新机构域 `{orgKey, orgName, subjectO, algorithm}`（自动建独立根CA+SUB_CA+门限份额；仅全局 ADMIN） |
| `POST /ssl/admin/orgs/{orgKey}/suspend\|resume\|close` | 机构状态流转（ACTIVE↔SUSPENDED→CLOSED 终态；关停仅门禁写操作，历史证书仍可验吊销） |
| `POST /ssl/admin/ca/init` | 初始化/重建根CA（幂等，私钥丢失时吊销旧根重建） |
| `POST /ssl/admin/ca/seal` | 封存根私钥（离线化，此后仅 SUB_CA 在线签发） |
| `POST /ssl/admin/ca/unseal` | 解封根私钥（轮换 SUB_CA 等维护窗口用） |
| `GET /ssl/admin/ca/root/download` | 导出根证书 |
| `GET /ssl/admin/ca/subca/download` | 导出 SUB_CA 证书（中间CA链） |
| `GET /ssl/admin/certs` | 证书列表（类型/状态/关键字筛选 + 分页） |
| `POST /ssl/admin/certs` | 签发证书 `{subjectCn, certType, algorithm, validityDays, san, description}` |
| `POST /ssl/admin/certs/csr` | CSR 签发 `{csrPem, certType, validityDays, san, description}` |
| `POST /ssl/admin/certs/public-key` | 公钥签发（RA 场景，申请方自持私钥） |
| `POST /ssl/admin/certs/{id}/revoke` | 吊销 `{reason}` |
| `POST /ssl/admin/certs/{id}/suspend` | 挂起（GM/T 0034 §12.6：进入 CRL certificateHold / OCSP on-hold，可恢复） |
| `POST /ssl/admin/certs/{id}/unsuspend` | 解除挂起（§12.7，恢复有效并移出 CRL） |
| `POST /ssl/admin/certs/{id}/renew` | 证书更新（§12.2：原证吊销 SUPERSEDED + 同公钥重签，私钥仍申请方自持） |
| `POST /ssl/admin/certs/{id}/rekey` | 密钥更新（§12.2：仅限 CA 持私钥的在线签发证书，换新密钥对重签） |
| `GET /ssl/admin/certs/{id}/cert.pem` | 下载证书（含 SUB_CA 中间CA链） |
| `GET /ssl/admin/certs/{id}/key.pem` | 下载私钥（仅 ADMIN/KEY_ADMIN + 开关控制，CSR 签发无私钥） |
| `POST /ssl/admin/certs/{id}/export/p12` | 导出 PKCS#12（body `{password}`，口令不进访问日志；仅 ADMIN/KEY_ADMIN） |
| `GET /ssl/admin/crl` | 吊销记录 + CRL PEM + thisUpdate/nextUpdate |
| `GET /ssl/admin/crl/download` | 下载 CRL |
| `GET /ssl/admin/crl/delta` | 增量 CRL 详情（PEM + 基础全量编号） |
| `GET /ssl/admin/audit` | 审计日志（登录/签发/吊销/封存/导出/账号与模板变更留痕；**仅 AUDITOR 可查询/验签/导出**） |
| `POST /ssl/admin/audit/verify` | 审计验签（防篡改 + 哈希链，仅 AUDITOR） |
| `GET /ssl/admin/audit/chain-verify` | 全链对账（按 id 序逐条自签名+prev_id 衔接核验，检出删除/分叉，仅 AUDITOR） |
| `GET /ssl/admin/audit/export` | 审计归档导出 CSV（UTF-8 BOM）+ 报告 SM2 签名（留存 ≥5 年材料） |
| `GET /ssl/admin/templates` | 证书模板管理（内置 SERVER_COMM/DOCTOR_SIGN/SUB_CA，签发锁定扩展项） |
| `GET/POST /ssl/admin/applications` | RA 申请-审核-签发流程（提交→批准/驳回→签发，审核人≠录入人） |
| `GET/POST /ssl/admin/ca/shares*` | 根密钥 Shamir(3,5) 门限分持：`GET /shares` 状态 / `POST /shares/migrate` 迁移 / `GET /shares/{index}` 下载单份 / `POST /unseal-shares` 3份解封（仅 ADMIN/KEY_ADMIN） |
| `GET/POST /ssl/admin/users` | 用户与角色管理（六类：ADMIN/OPERATOR/APPROVER/AUDITOR/SECURITY_ADMIN/KEY_ADMIN，仅 ADMIN 建号） |
| `GET /ssl/admin/overview` | 概览统计 |

## 关键设计

- **多机构域（多家独立医院共用一套 csssl）**：GDCA 多根家族式信任模型——每家机构一把独立根CA + 专属 SUB_CA + 独立密钥域/落盘目录（`store-dir/orgs/{orgKey}/`）/主密钥/门限份额，机构间互不信任（default 根验他域证书一律 valid=false）。机构注册表 `ssl_ca_org`（orgKey 受 `^[a-z0-9]{1,32}$` 校验兜路径穿越，subject_o 全局唯一），开机构即建根（幂等，首建中途崩溃留裸域可由 `/admin/ca/init` 补建 SUB_CA）。存量单根原样归位 `default` 域，老 URL 与落盘路径逐字节兼容；新机构叶证书主体 O 强制取注册表 `subject_o`
- **数据域服务端强制隔离**：各业务表加 `org_key`（default 域由 `DEFAULT` 自动归位，模板/用户 `NULL`=全局）；机构用户锁定本域（跨域读写一律 403，不信任前端传参），全局用户需显式 `?orgKey=` 才跨域，签发/CRL/OCSP/校验/审计/密钥全链路按 effectiveOrg 收口；SUSPENDED/CLOSED 机构只门禁写操作、历史证书仍可验证吊销（合规）
- **根CA离线化**：对齐国内持证CA通行架构——根CA仅初始化与签发 SUB_CA，日常签发/CRL/OCSP 全部由 SUB_CA 承担；根私钥支持「封存」离线（`key.pem.enc` 移存为 `.sealed`）或「Shamir(3,5) 门限分持」（推荐），封存后根密钥不参与任何在线操作，到期前 `renewBeforeDays` 天自动轮换
- **根密钥 Shamir(3,5) 门限分持（§8.2.4.3）**：根私钥 GF(256) 秘密共享拆 5 份份额、任 3 份恢复，门限化后原始 SM4 加密文件删除、份额成为唯一持久形态（新根自动触发/旧环境显式迁移接口）；份额解封仅内存缓存并以根证书公钥签名挑战校验（防篡改/错配），用后封存清除——对齐密评"根密钥不得单点持有"
- **私钥加密落盘**：所有私钥经 `KeyVault` 以 SM4 加密后落盘（`*.key.pem.enc`），主密钥由 `SSL_MASTER_KEY` 注入（未配置时首启随机生成落盘 `master.key`）；旧明文密钥自动迁移加密；HSM/加密机（GM/T 0018）可在同类接口处替换
- **吊销可发现性**：签发的证书带 CRL 分发点（CDP）与 OCSP 访问点（AIA）扩展；CRL 由 SUB_CA 签名并携带 thisUpdate/nextUpdate 与单调递增 CRLNumber；提供标准 OCSP 三态 responder（good/revoked/unknown，RFC 6960，POST+GET 双通道，nextUpdate 时效可配 `SSL_OCSP_NEXT_UPDATE_HOURS`）
- **增量 CRL（dCRL）**：`/ca/public/crl/delta` 只发布自最近全量以来新增的吊销/挂起条目，带 DeltaCRLIndicator 指向基础全量编号；发布状态持久化 `ssl_crl_state` 表（行锁保证多实例一致，取代旧 crl-state.json）；CRL 按"吊销集合指纹"判重发布——内容未变不递增编号不重签（公开拉取不可再被刷号），每日定时主动发布，依赖方全量+增量合并即可增量同步
- **证书生命周期**：签发→挂起/解除挂起（CRL certificateHold / OCSP on-hold）→更新 renew / 重签 rekey →吊销→过期归档，全流程审计留痕（GM/T 0034 §12）
- **证书链下载**：`/admin/certs/{id}/cert.pem` 返回叶子 + SUB_CA 完整链，TLS 服务端部署免手工拼接；软件证书场景支持 PKCS#12 打包导出（口令加密，叶子+SUB_CA+根完整链）
- **国密支持**：签发算法可选 `SM2`（SM3withSM2 签名、sm2p256v1 曲线），与 openssl / 国密工具链互通；`Sm4Util` 提供 SM4 加解密供 csmig 生产期使用
- **审计留痕**：登录/签发/吊销/挂起/导出/封存解封/建号改号停用/模板增删改全部落 `ssl_audit_log`；每条 SM3withSM2 签名 + SM3 哈希链（prev_id+prev_sign 双绑定：改任何一条即断链、**删中间记录由链对账检出**）；验签/链对账/导出仅 AUDITOR；归档导出 CSV 带报告签名（留存 ≥5 年）
- **分权制衡**：标准六类角色——系统管理员 ADMIN / CA操作员 OPERATOR / 审核员 APPROVER / 审计员 AUDITOR / 安全管理员 SECURITY_ADMIN（证书模板策略）/ 密钥管理员 KEY_ADMIN（根密钥门限分持与封存解封），签发与审核分离（审核人≠录入人，§10.1g），路由与菜单按角色显隐
- **鉴权对齐**：登录链路（SM2 加密口令 → BCrypt 校验 → JWT 携带 uid/token_version）与 cslk HIS、csmig 一致，前端复用 `sm-crypto`；改密/改角色/停用即递增版本号吊销全部旧会话，5 次口令错误锁定 15 分钟，新口令复杂度 ≥8 位含字母数字

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

## TODO（P2/P3 演进）

- [x] 时间戳服务（TSA，医疗签名"权威时间源+签名同时打戳"）——已交付
- [x] RA 申请-审核-签发双人审批、角色三权分立——已交付（P0/P1，四角色）→ 六类角色细化已交付（P2）
- [x] 证书更新/挂起/解除挂起/过期归档——已交付（P1）
- [x] 审计签名 + SM3 哈希链 + 归档导出——已交付（P1）
- [x] PKCS#12（.p12）打包下载——已交付（P2）
- [x] CRL 增量（dCRL）发布——已交付（P2）
- [ ] 双证书体系 + 密钥管理中心（KMC，加密证书托管，第三方持证才强制；院内豁免声明见 docs/）
- [ ] 国密 UKey 发证（GM/T 0016 保护结构、私钥不出设备）——需硬件采购
- [x] 根密钥 Shamir(3,5) 门限分持 + 标准六类角色细化——已交付（P2，`100ead3` 并入 master；份额生成/下载/3份解封 + SECURITY_ADMIN/KEY_ADMIN）
- [x] 多机构域（按机构独立根CA，多家医院共用一套 csssl）——已交付（路线 A：`ssl_ca_org` 注册表 + 每机构独立根/SUB_CA/密钥域/门限份额 + 全域 org_key 服务端强制隔离 + 机构管理页；`smoke-route-a.cjs` 24/24）
- [ ] 服务器密码机 HSM 对接（GM/T 0018）——生产/密评立项
- [ ] 远程 RA 分节点 + SCEP/CMP 标准协议（GM/T 0089）
- [ ] 管理后台口令修改页
