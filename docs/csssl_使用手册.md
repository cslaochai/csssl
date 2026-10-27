# csssl CA 根认证中心 · 使用手册（GM/T 0034 P0 版）

> 适用版本：master `713efa8`（feat/gm0034-p0 已合并）
> 适用对象：管理员 / CA 操作员 / 审核员 / HIS、医保前置对接开发
> 核心变化：新增 **多角色分权、审计日志签名防篡改、证书模板、RA 申请-审核-签发流程**；接口增加 `/admin/users`、`/admin/templates`、`/admin/applications`、`/admin/audit/verify`
> 生效条件：**合并后需重启后端一次**，启动时自动幂等升级数据库（新增 2 表 + 2 列），不影响既有数据

---

## 1. 系统概览

| 项 | 值 |
|---|---|
| 后端 | Spring Boot 3.2.5 / Java 21 / MyBatis-Plus，端口 **8092**，context-path **/ssl** |
| 数据库 | MySQL `ssl_ca`（192.168.88.132:3306，可用环境变量覆盖） |
| 前端 | Vue 3 + Element Plus 管理后台，Vite dev 端口 **5175**（代理 /ssl → 8092） |
| 密钥目录 | `./data/certs`（相对启动目录 `source/ssl_backend`；主密钥 `SSL_MASTER_KEY` 或首启生成 `master.key`） |
| 角色 | **ADMIN** 系统管理员 / **OPERATOR** CA操作员（录入+签发）/ **APPROVER** 审核员 / **AUDITOR** 审计员（只读） |

架构位置：`cslk(HIS 8080)` → `csmig(医保前置 8091)` → `csmis(医保局端 8090)`，csssl 为三者签发证书、提供 CRL/OCSP/TSA。

---

## 2. 快速启动

```bash
# 后端（首次启动自动建库建表、创建 admin 账号、内置 3 个证书模板）
cd source/ssl_backend
mvn spring-boot:run          # http://localhost:8092/ssl

# 前端管理后台
cd source/front
npm install
npm run dev                  # http://localhost:5175
```

可选环境变量：`SSL_DB_URL / SSL_DB_USERNAME / SSL_DB_PASSWORD / SSL_MASTER_KEY（32位hex，生产必设） / SSL_STORE_DIR / SSL_PUBLIC_BASE_URL`。

> ⚠️ 若此前旧版本正在运行（如你测试中的 8092 进程），请先停止旧进程再启动新代码；数据库升级由 `schema.sql` 幂等完成（`CREATE TABLE IF NOT EXISTS` + `information_schema` 加列），重复启动安全。

---

## 3. 首次初始化（一次性）

1. 打开管理后台 http://localhost:5175，用 `admin / 123456` 登录（**首次登录后尽快在"用户管理"里改密**）。
2. 进入 **根CA** 页面，点击"初始化根CA"（幂等；已存在则原样返回）。初始化后：
   - 生成根CA（默认 RSA2048，可在初始化参数选 SM2）与二级 CA（SUB_CA）证书；
   - 根私钥加密落盘；日常签发由 SUB_CA 承担。
3. 可选"封存根私钥"：将根密钥离线化（解封仅 ADMIN 可操作，用于 SUB_CA 轮换）。
4. 确认 **证书模板** 页面已有 3 个内置模板（见 §5），无需手工建。

---

## 4. 角色与账号管理（ADMIN 专属）

**菜单：用户管理（`/admin/users`）**，仅 ADMIN 可操作。建议为合规演示建 3 个账号：

| 角色 | 示例账号 | 初始口令 | 能做什么 | 不能做什么 |
|---|---|---|---|---|
| ADMIN | admin | 123456 | 用户管理、根CA维护、全部操作 | — |
| OPERATOR | ca_operator | 123456 | 录入申请、执行签发/吊销 | 审核、用户管理 |
| APPROVER | ca_approver | 123456 | 审核批准/驳回申请 | 录入/签发（且不得审核自己录入的申请） |
| AUDITOR | ca_auditor | 123456 | 查看证书/审计/验签 | 一切写操作 |

创建示例（POST `/admin/users`，ADMIN token）：
```json
{"username":"ca_operator","realName":"CA操作员","roleCode":"OPERATOR","password":"123456"}
```
> 权限校验是**接口级**的（RoleCode.require），角色不符返回 403。签发/吊销/申请提交需 OPERATOR 或 ADMIN；审核需 APPROVER 或 ADMIN；模板/用户管理需 ADMIN。

---

## 5. 证书模板（内置 3 个，签发按模板锁定扩展项）

**菜单：证书模板（`/admin/templates`）**。内置模板启动自动创建，`builtin=1` 不可删除（可改参数）：

| 模板编码 | 名称 | 类型 | 算法 | 有效期 | KeyUsage | EKU | 策略OID |
|---|---|---|---|---|---|---|---|
| SERVER_COMM | 机构通信证书 | SERVER | SM2 | 730天 | digitalSignature,keyEncipherment | serverAuth | 1.2.156.112562.2.2.15 |
| DOCTOR_SIGN | 医生签名证书 | CLIENT | SM2 | 365天 | digitalSignature | clientAuth | 1.2.156.112562.2.2.15 |
| SUB_CA | 二级CA签发证书 | SUB_CA | RSA2048 | 730天 | keyCertSign,cRLSign | — | — |

用途：**签发时传 `templateId`，类型/算法/有效期/KeyUsage/EKU/策略OID 全部以模板为准**（GM/T 0034 §5.3.4），杜绝手工拼扩展项；证书记录会落 `template_id` 可追溯。
按需自定义：ADMIN 可创建（如"前置机SM2通信证书"自定义模板），KeyUsage 支持 digitalSignature / keyEncipherment / keyCertSign / cRLSign 等。

---

## 6. RA 申请-审核-签发流程（推荐入口，合规必经）

**菜单：证书申请（`/admin/applications`）**。状态机：`PENDING → APPROVED / REJECTED → ISSUED / CANCELLED`。

流程（对应 §5.2.2 + §10.1g 录入审核分离）：

1. **提交申请（OPERATOR/ADMIN）**——代录医生/机构材料：
   ```json
   POST /admin/applications
   {
     "applicantType": "PERSON",            // INSTITUTION-机构 / PERSON-个人
     "applicantName": "张三",
     "idNo": "430102199001011234",         // 机构传统一社会信用代码
     "subjectDn": "CN=张三,O=麓康医院,OU=门诊部",
     "templateId": 2,                      // 用内置 DOCTOR_SIGN 模板
     "publicKeyPem": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",  // 申请方自持私钥，仅提交公钥
     "san": "", "description": "门诊医师电子签名证书"
   }
   ```
   返回申请单号 `RA20261027...`，状态 PENDING。
2. **审核（APPROVER/ADMIN，必须 ≠ 提交人）**：
   - 批准：`POST /admin/applications/{id}/approve` `{"comment":"材料核验通过"}`
   - 驳回：`POST /admin/applications/{id}/reject` `{"comment":"证件号与执业信息不符"}`
   - **审核人与提交人同一人 → 403**（违反 §10.1g 分权制衡）。
3. **签发（OPERATOR/ADMIN）**：`POST /admin/applications/{id}/issue`——复用模板+公钥签发，私钥始终在申请方手中，服务器只落证书；签发后申请单状态 ISSUED 并回填证书ID。
4. 撤销：`POST /admin/applications/{id}/cancel`（仅 PENDING/APPROVED 可撤）。

> 医生/前置机侧生成密钥对 → 提交公钥 → 领回证书。**签名私钥不出申请方设备**，这是 RA 场景与"在线签发（服务器生成私钥）"的本质区别。

---

## 7. 直接签发（三种方式，不走 RA 时用）

| 方式 | 接口 | 适用 |
|---|---|---|
| 在线签发 | `POST /admin/certs` | 服务器生成密钥对并落盘私钥（开发/测试便捷） |
| CSR 签发 | `POST /admin/certs/csr` | 申请方已生成 CSR（PKCS#10） |
| 公钥签发 | `POST /admin/certs/public-key` | 申请方提交 DN+公钥，私钥自持（HIS 医生证书对接） |

在线签发示例：
```json
POST /admin/certs
{"subjectCn":"csmig-pre","certType":"SERVER","algorithm":"SM2","validityDays":365,"san":"192.168.88.130","templateId":1,"description":"医保前置机通信证书"}
```
吊销：`POST /admin/certs/{id}/revoke` `{"reason":"私钥泄露/人员离职"}` → 立即进 CRL，OCSP 状态转 revoked。
下载：`GET /admin/certs/{id}/cert.pem`（证书）；`GET /admin/certs/{id}/key.pem`（私钥，受 `allow-private-key-download` 开关控制，生产建议关闭）。

---

## 8. 证书状态校验（依赖方调用）

| 接口 | 方法 | 说明 |
|---|---|---|
| `GET /ssl/ca/public/root` | GET | 根证书 PEM（信任锚） |
| `GET /ssl/ca/public/subca` | GET | SUB_CA 证书 PEM |
| `GET /ssl/ca/public/crl` | GET | 最新 CRL（PEM，含签名与 thisUpdate/nextUpdate） |
| `POST /ssl/ca/public/ocsp` | POST | OCSP 三态（Content-Type: application/ocsp-request，DER；返回 application/ocsp-response） |
| `POST /ssl/ca/public/verify` | POST | 证书链校验（业务快速验证接口） |
| `GET /ssl/admin/crl/download` | GET | 管理端 CRL 下载 |

证书内置 **CDP**（CRL 分发点）与 **AIA**（OCSP 地址）扩展，依赖方（HIS 各服务 / 前置机）自动发现吊销源。

---

## 9. 审计与验签（GM/T 0034 §10.1/§10.5）

- 查询：`GET /admin/audit?action=CERT_ISSUE&pageNo=1&pageSize=10`
- **防篡改验签**：`POST /admin/audit/verify` `{"id": 123}`
  返回 `{valid: true/false, canonical, sign}`。每条日志在写入时对六字段规范化串做 **SM3withSM2 签名**（独立审计密钥，私钥 SM4 加密落盘 `data/certs/audit-sign/`）；任何人改动任一字段，验签即失败。
- 可演示：正常记录验签 valid=true → 手改 DB 里某条 detail → 再验签 valid=false。

---

## 10. TSA 可信时间戳（病历签名时间证明）

| 接口 | 说明 |
|---|---|
| `POST /ssl/tsa/stamp` `{"digestHex":"<64位SM3/SHA256摘要>"}` | 盖章，返回 serial / tsaTime / token（SM3withSM2 令牌） |
| `GET /ssl/tsa/publicKey` | TSA 公钥（业务系统验签用） |

HIS 病历签名流程：计算病历摘要 → 调 TSA 盖章 → 把 token 与签名一并落库；事后可用 TSA 公钥验证"该时刻确实存在"。

---

## 11. 与 HIS / 医保链路对接（证书签发点）

| 场景 | 操作 | 建议模板 |
|---|---|---|
| HIS 各微服务服务端证书（TLS/mTLS） | 在线签发 SERVER 证书，配 HTTPS | SERVER_COMM |
| 医生电子签名证书 | 医生侧生成 SM2 密钥对 → RA 申请（公钥）→ 签发 | DOCTOR_SIGN |
| 医保前置机（csmig）通信证书 | 在线/CSR 签发，SAN 填前置机 IP | SERVER_COMM |
| 报文加签加密 | 复用已签证书私钥：SM2 签名 + SM4 加密（CSSSL-SEC-V1 信封，见 csmig 联调指南） | — |
| 病历时间证明 | TSA 盖章 | — |

信任根下发：`GET /ssl/ca/public/root` 拉取根证书配置到各端 trustStore。

---

## 12. REST 接口速查表

| 分类 | 接口 | 角色 |
|---|---|---|
| 登录 | `GET /ssl/auth/publicKey` / `POST /ssl/auth/login`（口令 SM2 加密）/ `POST /ssl/auth/logout` | — |
| 根CA | `GET /ssl/admin/ca/status`、`POST /ssl/admin/ca/init|seal|unseal` | 仅 ADMIN |
| 用户 | `GET/POST /ssl/admin/users`、`POST /ssl/admin/users/{id}` | 仅 ADMIN |
| 模板 | `GET /ssl/admin/templates`、`POST /ssl/admin/templates`、`POST /ssl/admin/templates/{id}[/delete]` | 查询登录即可，写仅 ADMIN |
| 证书 | `GET /ssl/admin/certs`、`POST /ssl/admin/certs[|/csr|/public-key]`、`POST /ssl/admin/certs/{id}/revoke`、`GET /ssl/admin/certs/{id}/cert.pem[|/key.pem]` | 签发/吊销需 OPERATOR/ADMIN |
| 申请单 | `GET /ssl/admin/applications`、`POST /ssl/admin/applications`、`POST /ssl/admin/applications/{id}/approve|reject|issue|cancel` | 提交/签发 OPERATOR/ADMIN，审核 APPROVER/ADMIN |
| 审计 | `GET /ssl/admin/audit`、`POST /ssl/admin/audit/verify` | 登录即可 |
| 公共 | `GET /ssl/ca/public/root|subca|crl`、`POST /ssl/ca/public/verify|ocsp` | 匿名 |
| TSA | `POST /ssl/tsa/stamp`、`GET /ssl/tsa/publicKey` | 匿名（业务系统调用） |

> 管理类接口统一 `Authorization: Bearer <token>`；命令行联调时登录口令需先按 `/auth/publicKey` 做 SM2 加密（可复用 csmig 联调工具 `InsCryptoTool` / `bare_call.ps1` 的加解密逻辑，或直接走前端页面）。

---

## 13. 常见问题

| 问题 | 处理 |
|---|---|
| 启动后表没变？ | 确认用新代码启动（master `713efa8`），schema.sql 幂等自动执行 |
| 8092 被占用 | 旧进程未停；停掉后重启，或 `SSL_...` 环境变量临时换端口验证 |
| 角色不符 403 | 检查该接口所需角色与登录账号角色（登录返回体含 `role`） |
| 审核自己录入的申请 403 | 用 APPROVER 账号审（换人），符合 §10.1g |
| 根私钥在哪 | `data/certs/root.key.pem.enc`（SM4 加密），主密钥 `master.key` 或 `SSL_MASTER_KEY` |
| 生产部署 | 必须设 `SSL_MASTER_KEY`；建议根私钥封存离线、签发只走 SUB_CA；后续计划 UKey/密码机（见规划文档） |

---

## 14. 当前边界（P0 之后还没做的）

- 证书**更新 / 延期 / rekey**、**挂起 / 解除挂起**、**归档策略**（规划 P1，密评生命周期必查）
- KMC **豁免声明 + CP/CPS 文档**（规划 P1）
- 前端适配 P0 新页面（角色/模板/RA）——**当前新接口仅命令行可用**，页面在规划 P1
- UKey 发证（GM/T 0016）、密码机 HSM（规划 P2/P3）
- 详细规划见 `docs/csssl_CA厂商差距分析与建设规划.md`

> 验收建议流程：登录 → 初始化根CA → 建 OPERATOR/APPROVER/AUDITOR → 查模板 → OPERATOR 提交医生申请 → APPROVER 批准 → OPERATOR 签发 → `POST /ca/public/verify` 验链 → OCSP 查状态 → 审计验签通过。
