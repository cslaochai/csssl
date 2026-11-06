# csssl 项目约定

## 前端列表页统一风格（两卡式，口径对齐 cslk PatientsView）

所有 查询条件+分页 的列表页必须按此结构，不允许各页自创布局：

- **查询卡 + 表格卡两张 `el-card shadow="never"`**，卡间距 12px；页面不再包 `.page-card` 大卡（非列表页的概览/根CA/OCSP/TSA 仍可用 `.page-card`）。
- 查询卡：`<el-card ref="queryCardRef" class="query-card">` 内 `.query-wrap` 两段式——左侧 `el-form inline`，**每个查询条件一个带 `label` 的 `el-form-item`**（下拉 placeholder 用「全部」，输入框 clearable + `@keyup.enter` 搜索），固定尾项放 搜索（`:icon="Search"` primary）/ 重置（`:icon="Refresh"`）；右侧 `.query-actions` 放页面级操作按钮（新建/签发/下载）与「当前域」标签（域标签不受按钮权限 v-if 影响，v-if 只包按钮）。
- 表格卡：`class="table-card"`（body 零内边距，表格全幅贴边）；`el-table` 用 `v-loading` `:max-height="tableMaxHeight"` `stripe`，**不加 `border`**；单号/序列号/账号列挂 `class-name="cell-mono"`；单元格副行用 `.cell-sub`；操作列 `align="right"`、link 按钮。
- **操作列按钮最多平铺 3 个**，超出收进「更多」下拉（el-dropdown，`trigger="click"`）：页面写 `xxxActions(row)` 返回动作数组（含 label/type/disabled/run），权限过滤用 `hasPerm()` 写进动作构建（不再用模板 v-perm），`visibleRowActions/moreRowActions` 按 `ACTION_VISIBLE=3` 切分。范例：`Certs.vue`、`Applications.vue`。
- 空态用一行文案 `<div class="empty-tip">暂无匹配的××</div>`，不用 `el-empty`。
- 分页：`.list-footer`（`ref="footerRef"`）钉表格卡底部右侧，`layout="total, sizes, prev, pager, next, jumper"`；档位与默认值一律引用 `lib/pagination.js` 的 `PAGE_SIZES` / `DEFAULT_PAGE_SIZE`，禁止页面写死。
- 表格限高一律用 `lib/useTableMaxHeight.js`（查询卡 ref + footer ref + max-height 三件套）；筛选变化 `handleSearch` 回第 1 页，翻页/换档只改 `pageNo/pageSize` 后直接 `load`，**不要把翻页绑回会重置页码的函数**。
- 标题类文案不带括号式说明（如「机构管理（多机构·…）」），能力说明写进文档不写进页面标题。

实现范例页：`source/front/src/views/Companies.vue`（最简）、`Crl.vue`（带 tab）、`Certs.vue`（带权限按钮组+更多收纳）。

## 审批类列表取数口径（单必归属到人）

待办/审批类列表禁止「同域全员可见可批」，也不允许前端传"只看我的"开关：

- **数据归属**：单据提交时必须指派具体处理人（对外 API 通道无人可挑，由服务端按积压最少自动分派），归属字段是审批准入条件，不只是通知对象。
- **可见范围**：服务端按登录人角色强制收口——处理人看「指派给我的 + 无人认领的」，发起人看「我提的」，下游环节看「待我处理的」，流程监管与只读巡检角色保持数据域全量。查询 DTO 不增设处理人参数。
- **准入校验**：批准/驳回接口除状态、数据域、双人复核外，必须校验「当前登录人 == 被指派处理人」。
- **按钮显隐**：由服务端逐行回算一个 `reviewable` 随列表返回，前端只看它（不再自己拼 `hasPerm`），保证前后端同一口径；不可处理的动作**直接不渲染**，禁止置灰加解释文案。
- 处理人停用/被删时单据回落到处理人池共同接管，避免永久卡死。范例：`CertApplicationService#applyViewerScope` / `#checkAssignee`。

## 人员身份口径（业务表禁止存登录账号）

登录账号 `ssl_sys_user.username` 是可登录凭据的一半，**只允许存在用户表这一处**；业务表、审计 detail 文案、消息正文、接口出参一律不得出现账号：

- 人员列一律 `xxx_user_id BIGINT`（指向 `ssl_sys_user.id`）+ 配套的真实姓名快照列（`xxx_name` / `xxx_real_name`）；名单类列同理，用「idCSV + 姓名CSV」两组同序，不用账号CSV。
- 姓名**只在写入时**从 `UserUtils.getLoginUser().getRealName()` 取快照落库，读的时候直接读那一列。**禁止**写「按账号回查 `ssl_sys_user` 拼姓名」的 helper——既是 N+1，又是账号泄露面。
- 无登录态的自动流转/系统写入：`user_id` 留 NULL、姓名写「系统」（`AuditMetaObjectHandler.SYSTEM_NAME`），不许造 `id=0` 或 `api:xxx` 这类伪账号；对外 API 通道的操作人姓名写 `ssl_api_client.client_name`。
- 6 个通用审计列（创建人ID/姓名/创建时间 + 修改人ID/姓名/修改时间）由 `AuditMetaObjectHandler` 自动填：实体一律 `extends AuditColumns`，业务代码不许手工 `setCreatedAt(...)`。正在做的工作流（`flow_*` 表 + `com.csssl.workflow`）暂不挂这组列。
- 人的比较一律 `Objects.equals(...getUserId(), ...)`；查询条件、`LambdaQueryWrapper` 方法引用、裸列名全部用 `*_user_id`。
- 允许显示账号的只有四处：用户与角色管理页、登录页演示凭据弹窗、审批人下拉、保管人下拉。
- `ssl_sys_user` 没有 `company_uscc`，`LoginUser` 也不带它：人不挂公司，操作哪家公司由请求参数显式声明 + 角色权限点收口，口径集中在 `CompanyScope`（只认请求参数，没有 operatorCompany 首参，也没有 `requireRowVisible`）。

## 发证只有两条路径（禁止直发）

- 叶子证书：RA 申请单（`/admin/applications` 提交→被指派审批人批准→持 `app:issue` 按批准快照签发）。
- 二级CA：`/admin/ca/subca-rebuild`（5 名保管人 3-of-5 审批 + 份额解封后用根CA 重签），叶子证书不得由 SUB_CA 之外的路径签。
- **不得新增任何直接签发端点/服务方法/权限点**：`POST /admin/certs`、`/admin/certs/csr`、`/admin/certs/public-key` 与 `cert:issue` 已删除（含 `IssueCertRequestDTO`/`CsrIssueRequestDTO`、前端签发按钮与 `issueCert*` 封装），对外 API 通道 `/ca/api/v1` 也只有 apply/cancel/revoke。
- 「证书管理」页是台账不是发证入口：不要在那儿加签发按钮；`CertService#issueWithPublicKey(companyUscc, req, policy, ...)` 是唯一发证出口，且只允许 `CertApplicationService#issue` 调用。

## 常用命令

- 后端：`source/ssl_backend` 下 `mvn -q -DskipTests compile`；本机 8092 是开发实例，验证新接口另起 8093：`mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=8093`（测完只杀自己起的进程，勿动 8092）。
- 前端构建校验：`source/front` 下 `npx vite build --outDir .verify-build --emptyOutDir`。
- Shell 为 Git Bash：**Bash 命令行内不要出现中文**（中文提交用 `git commit -F 临时文件`）；mvn 日志是 GBK；前端源文件保持 CRLF。

## 密钥保险库（KEK）口径

- `ssl_key_vault` 的密文**只认写入时那套主密钥**：default 域（`company_uscc IS NULL`，TSA/审计签名）认 `store-dir/master.key`，公司域认 `store-dir/companies/{uscc}/master.key`。表里没有 KEK 版本标识，所以「换启动目录 / 换 `SSL_STORE_DIR`」等于换了一把 KEK，症状是接口 500、日志 `BadPaddingException: pad block corrupted`。
- dev 已把 `store-dir` 固定为绝对路径 `E:/work_source/his_cslk/data/certs`（`application-dev.yml`，仍可用 `SSL_STORE_DIR` 覆盖）。**不要拿另一套盘去写共享库**——多套 KEK 混写同一个库就是 2026-11-05 那次 TSA 解密失败的原因。
- 启动日志有一行 `[密钥保险库] default 域 store-dir=… KEK 指纹=…`；两个实例指纹不一致，就是两套 KEK，别指望它们互相解得开。
- 自救口径：**可再生的密钥**（`tsa`、`audit-sign`）删掉 `ssl_key_vault` 对应条目后重启即自动重建（TSA 会换公钥，历史令牌随之失效）；`SUB_CA` / `api-clients` 等**必须找回原 KEK**，重建等于废掉已签发证书与已分发凭证。若能找到"旧 KEK"文件，优先用旧 KEK 解密 + 当前 KEK 重新加密回写，密钥对不变、历史签名继续可验。

## 清库重建（历史数据清零）

- 库表结构**不自动建**（`sql.init` 未开启）：清库后必须手工执行 `source/ssl_backend/db/` 下的脚本。
- ⚠️ **运维脚本一律放 `source/ssl_backend/db/`**，不要放 `src/main/resources/db/`：maven resources 插件会把 `target/classes/db/` 整目录**覆盖**回 resources，放那儿的脚本会被静默删掉。（`src/main/resources/db/` 只留 `schema.sql`，应用启动要读它。）
- **一键脚本**（推荐）：`bash source/ssl_backend/db/reset-dev-db.sh [host] [port] [db] [user]`
  → ① `reset.sql` DROP 全部 21 张业务表 → ② 导入 `schema.sql` 重建 18 张表 + 种子 → ③ `init-key-admins.sql` 建 5 名平台级密钥管理员 → ④ 清空磁盘 `store-dir`。
  口令经环境变量 `MYSQL_PWD` 传入（不出现在命令行里）；需 PATH 中有 mysql 客户端。
- **纯 SQL**：`mysql -h <host> -u <user> -p <db> < source/ssl_backend/db/reset.sql`，再导入 `schema.sql`。
- ⚠️ **`reset.sql` 会连用户账号一起删**。只清业务数据、保留账号 → 改用 `db/reset-keep-users.sql`（保留 `ssl_sys_user` / `ssl_sys_role` / `ssl_role_perm`）。
- 🔁 **保留历史数据只做结构升级**（不改口径的常规发版不要跑它）：`db/migrate-actor-user-id.sql` —— 幂等可重跑，给业务表加 `*_user_id` + 姓名快照、把账号列的数据搬过去后 DROP 旧列，补 6 个通用审计列与 `ssl_audit_log.sign_ver`，最后删 `ssl_sys_user.company_uscc`。清库路线（`reset.sql` + `schema.sql`）与迁移路线二选一，别混着跑。
- ⚠️ **清库前必须先备份**（库 + 磁盘 `master.key`）：
  - 备份库：导出为 SQL（结构+数据）即可回灌；但 `cert_pem` 等 PEM 文本含真实换行，回灌时 INSERT 语句**跨多行**，须累积到以 `);` 结尾才算一条。
  - 备份 `store-dir/master.key`：旧 `ssl_key_vault` 密文依赖它，丢了旧私钥**永久解不开**。
  - 回灌时若角色编码改造过（如 `TENANT_ADMIN` → `COMPANY_ADMIN`），须先改 `ssl_sys_user.role_code`，再把旧角色的权限点按映射并入新角色，最后删旧角色档案。
- ⚠️ **磁盘必须一起清**（`store-dir`，默认 `ssl_backend/data/certs`）：残留的**旧根CA Shamir 份额文件**会被 `doEnsureRootShares` 的"5 份齐全"判据误命中 → 新公司拿到旧根私钥的份额 → 后续贴份额解封时签名挑战必然失败（"份额重组密钥与根证书不匹配"）。已修 `CaService.doClearShareFiles`，但清库时清盘仍是首选。
- 🔑 **「新建公司」的保管人下拉必须先有 5 个 KEY_ADMIN 账号**，否则下拉为空、根本建不了公司（`db/init-key-admins.sql` 建 keyman1~5 / 口令 123456 / 平台级）。
  根因：下拉只认 KEY_ADMIN 角色（分权制衡，排除系统管理员等冲突角色）；而**建公司时该公司账号尚不存在** → 只能指定**平台级**账号（`company_uscc = NULL`）。公司建好后可在「公司详情 → 重新指定保管人」改指为公司自有账号。
- 清库后启动后端会自动播种：`admin/123456` + 12 个预置角色 + 角色授权 + 3 个内置证书模板；**公司需手工新建**（新建即建独立根CA + SUB_CA + 5 份门限份额）。
- 旧根私钥文件丢失时，启动会**自愈重建**主域根CA + SUB_CA + 5 份份额（落 `data/certs/companies/{uscc}/`）；`DataInitializer` 会按"法定上限"裁剪越界授权。两者都是正常行为，看到数量变化不必惊慌。
- 造测试用统一社会信用代码时注意 GB 32100 字符集**不含 I/O/S/V/Z**，末位校验码 = `(31 - 加权和 mod 31) mod 31`。
