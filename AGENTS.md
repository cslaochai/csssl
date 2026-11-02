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

## 常用命令

- 后端：`source/ssl_backend` 下 `mvn -q -DskipTests compile`；本机 8092 是开发实例，验证新接口另起 8093：`mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=8093`（测完只杀自己起的进程，勿动 8092）。
- 前端构建校验：`source/front` 下 `npx vite build --outDir .verify-build --emptyOutDir`。
- Shell 为 Git Bash：**Bash 命令行内不要出现中文**（中文提交用 `git commit -F 临时文件`）；mvn 日志是 GBK；前端源文件保持 CRLF。

## 清库重建（历史数据清零）

- 库表结构**不自动建**（`sql.init` 未开启）：清库后必须手工执行 `source/ssl_backend/src/main/resources/db/` 下的两个脚本。
- **一键脚本**（推荐）：`bash source/ssl_backend/src/main/resources/db/reset-dev-db.sh [host] [port] [db] [user]`
  → ① `reset.sql` DROP 全部 21 张业务表 → ② 导入 `schema.sql` 重建 18 张表 + 种子 → ③ 清空磁盘 `store-dir`。
  口令经环境变量 `MYSQL_PWD` 传入（不出现在命令行里）；需 PATH 中有 mysql 客户端。
- **纯 SQL**：`mysql -h <host> -u <user> -p <db> < source/ssl_backend/src/main/resources/db/reset.sql`，再导入 `schema.sql`。
- ⚠️ **磁盘必须一起清**（`store-dir`，默认 `ssl_backend/data/certs`）：残留的**旧根CA Shamir 份额文件**会被 `doEnsureRootShares` 的"5 份齐全"判据误命中 → 新公司拿到旧根私钥的份额 → 后续贴份额解封时签名挑战必然失败（"份额重组密钥与根证书不匹配"）。已修 `CaService.doClearShareFiles`，但清库时清盘仍是首选。
- 清库后启动后端会自动播种：`admin/123456` + 12 个预置角色 + 角色授权 + 3 个内置证书模板；**公司需手工新建**（新建即建独立根CA + SUB_CA + 5 份门限份额）。
- 造测试用统一社会信用代码时注意 GB 32100 字符集**不含 I/O/S/V/Z**，末位校验码 = `(31 - 加权和 mod 31) mod 31`。
