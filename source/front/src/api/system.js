import request from './request'

/**
 * CSSSL · CA根认证中心 API 封装（与后端 /ssl context-path 对齐）。
 * Admin 后台业务接口一律 POST + JSON body，无 path 参数、无 query 参数；
 * 仅公网 PKI 协议端点（/auth/publicKey、/tsa/publicKey、/ca/public/**）保留 GET/路径段。
 */

// ===== 登录鉴权 =====
export function getPublicKey() {
  return request.get('/auth/publicKey')
}
export function login(data) {
  return request.post('/auth/login', data)
}
export function logout() {
  return request.post('/auth/logout')
}
/** 修改本人登录口令（oldPassword/newPassword 均为 SM2 密文，成功后会话失效需重新登录） */
export function changePassword(data) {
  return request.post('/auth/changePassword', data)
}

// ===== 公司管理 =====
export function listCompanies() {
  return request.post('/admin/companies/list', {})
}
/** 新建公司（直接执行：建公司即建独立根CA+SUB_CA 并发放接入凭证；返回一次性凭证回执） */
export function createCompany(data) {
  return request.post('/admin/companies/create', data)
}
/** 公司分页查询：{pageNo,pageSize,keyword,status} */
export function pageCompanies(params) {
  return request.post('/admin/companies/page', { ...params })
}
export function getCompany(companyUscc) {
  return request.post('/admin/companies/detail', { companyUscc })
}

// ===== 公司变更单（暂停/恢复/关闭/重置接入密钥：发起→他人审批→批准即执行） =====
/** 变更单分页查询：{pageNo,pageSize,status,opType,keyword} */
export function pageCompanyOps(params) {
  return request.post('/admin/company-ops/page', { ...params })
}
/** 发起变更单：{opType,uscc,reason,assigneeUserId?} */
export function submitCompanyOp(data) {
  return request.post('/admin/company-ops/submit', data)
}
/** 审批批准（不得与发起人同一人；批准即执行） */
export function approveCompanyOp(id, comment) {
  return request.post('/admin/company-ops/approve', { id, comment })
}
/** 审批驳回（意见必填） */
export function rejectCompanyOp(id, comment) {
  return request.post('/admin/company-ops/reject', { id, comment })
}
/** 撤回（仅发起人本人、仅待审批） */
export function cancelCompanyOp(id) {
  return request.post('/admin/company-ops/cancel', { id })
}
/** 原保管人投票（重新指派保管人单专用：{agree,comment}） */
export function voteCompanyOp(id, data) {
  return request.post('/admin/company-ops/vote', { id, ...data })
}
/** 领取接入凭证回执（仅发起人本人、一次性） */
export function takeCompanyOpReceipt(id) {
  return request.post('/admin/company-ops/receipt', { id })
}
/** 审批轨迹（按步时间正序） */
export function listCompanyOpTrail(id) {
  return request.post('/admin/company-ops/trail', { id })
}
/** 催办（仅提交人可催办） */
export function urgeCompanyOp(id) {
  return request.post('/admin/company-ops/urge', { id })
}
/** 变更单详情（待办聚合页深链直达用） */
export function detailCompanyOp(id) {
  return request.post('/admin/company-ops/detail', { id })
}

/** 公司列表模块级缓存（各页公司下拉共用） */
let companiesCache = null
export async function listCompaniesCached(force = false) {
  if (companiesCache && !force) return companiesCache
  try {
    const res = await listCompanies()
    const d = res.data
    companiesCache = Array.isArray(d) ? d : d?.records || []
  } catch (e) {
    companiesCache = companiesCache || []
  }
  return companiesCache
}

/** 公司标识→中文名映射（缓存清单）：界面展示一律中文名优先，未命中回落标识 */
export async function companyNameMap() {
  const map = {}
  for (const t of await listCompaniesCached()) {
    if (t.uscc) map[t.uscc] = t.companyName || t.uscc
  }
  return map
}

// ===== 根CA（按公司域） =====
export function getCaStatus(companyUscc) {
  return request.post('/admin/ca/status', { companyUscc })
}
export function initCa(companyUscc) {
  return request.post('/admin/ca/init', { companyUscc })
}
export function downloadRootCert(companyUscc) {
  return request.post('/admin/ca/root/download', { companyUscc })
}
export function sealRootKey(companyUscc) {
  return request.post('/admin/ca/seal', { companyUscc })
}
export function unsealRootKey(companyUscc) {
  return request.post('/admin/ca/unseal', { companyUscc })
}
export function downloadSubCaCert(companyUscc) {
  return request.post('/admin/ca/subca/download', { companyUscc })
}

// ===== 根密钥 Shamir(3,5) 门限分持 =====
export function getCaShares(companyUscc) {
  return request.post('/admin/ca/shares', { companyUscc })
}
export function migrateCaShares(companyUscc) {
  return request.post('/admin/ca/shares/migrate', { companyUscc })
}
export function downloadCaShare(index, companyUscc) {
  return request.post('/admin/ca/shares/download', { index, companyUscc })
}
export function unsealCaShares(shares, companyUscc) {
  return request.post('/admin/ca/unseal-shares', { shares, companyUscc })
}

// ===== 根密钥份额保管人绑定 =====
/** 指定/重指定根私钥份额保管人：{companyUscc, holderUsernames[5]} */
export function assignShares(data) {
  return request.post('/admin/ca/shares/assign', data)
}
/** 某公司密钥份额总览：{holders, mine, threshold, total} */
export function getShareOverview(companyUscc) {
  return request.post('/admin/ca/shares/overview', { companyUscc })
}

// ===== 用户 =====
/** 传 companyUscc 则收敛为「平台级 + 该公司本域」的密钥管理员；不传则只返回平台级 */
export function listSimpleUsers(companyUscc) {
  return request.post('/admin/users/simple', { companyUscc })
}

// ===== 二级CA 重建申请单 =====
/** 发起重建申请：{companyUscc, reason} */
export function applySubCaRebuild(data) {
  return request.post('/admin/ca/subca-rebuild/apply', data)
}
/** 申请单分页：{pageNo,pageSize,status,keyword,companyUscc} */
export function pageSubCaRebuild(params) {
  return request.post('/admin/ca/subca-rebuild/page', { ...params })
}
/** 当前登录人作为保管人且尚未投票的待审单 */
export function pendingSubCaRebuild() {
  return request.post('/admin/ca/subca-rebuild/pending', {})
}
/** 当前登录人作为发起人且已审批通过（待执行）的申请单 */
export function myExecutableSubCaRebuild() {
  return request.post('/admin/ca/subca-rebuild/mine-executable', {})
}
/** 申请单详情 */
export function detailSubCaRebuild(id) {
  return request.post('/admin/ca/subca-rebuild/detail', { id })
}
/** 审批同意：{id, comment, shareJson} */
export function approveSubCaRebuild(id, data) {
  return request.post('/admin/ca/subca-rebuild/approve', { id, ...data })
}
/** 审批驳回：{id, comment} */
export function rejectSubCaRebuild(id, data) {
  return request.post('/admin/ca/subca-rebuild/reject', { id, ...data })
}
/** 撤回申请（仅发起人本人，且仅 PENDING） */
export function cancelSubCaRebuild(id) {
  return request.post('/admin/ca/subca-rebuild/cancel', { id })
}
/** 执行重建：{id, useCollectedShares:true} 或 {id, shareJsons[≥3]} */
export function executeSubCaRebuild(id, data) {
  return request.post('/admin/ca/subca-rebuild/execute', { id, ...data })
}
/** 审批轨迹 */
export function listSubCaRebuildTrail(id) {
  return request.post('/admin/ca/subca-rebuild/trail', { id })
}
/** 催办尚未投票的保管人（仅发起人本人） */
export function urgeSubCaRebuild(id) {
  return request.post('/admin/ca/subca-rebuild/urge', { id })
}

// ===== 证书管理（只读台账 + 生命周期操作单） =====
export function pageCerts(params) {
  return request.post('/admin/certs/page', { ...params })
}
export function getCert(id) {
  return request.post('/admin/certs/detail', { id })
}
export function downloadCertPem(id) {
  return request.post('/admin/certs/cert-pem', { id })
}
export function downloadKeyPem(id) {
  return request.post('/admin/certs/key-pem', { id })
}
/** 导出证书压缩包（ZIP，不加密）：内含证书链 PEM */
export function downloadCertZip(id) {
  return request.post('/admin/certs/export-zip', { id }, { responseType: 'blob' })
}
/** 证书概览（按公司域） */
export function getOverview(companyUscc) {
  return request.post('/admin/overview', { companyUscc })
}

// ===== CRL 吊销列表（按公司域） =====
export function getCrlView(companyUscc) {
  return request.post('/admin/crl/view', { companyUscc })
}
/** 吊销记录分页查询：{pageNo,pageSize,keyword,status,reason,companyUscc?} */
export function pageCrlRecords(params) {
  return request.post('/admin/crl/records', { ...params })
}
export function downloadCrl(companyUscc) {
  return request.post('/admin/crl/download', { companyUscc })
}
/** 增量 CRL（dCRL） */
export function getDeltaCrl(companyUscc) {
  return request.post('/admin/crl/delta', { companyUscc })
}

// ===== 审计日志 =====
export function pageAudit(params) {
  return request.post('/admin/audit/page', { ...params })
}
export function verifyAuditLog(id) {
  return request.post('/admin/audit/verify', { id })
}
/** 哈希链对账（区间可空）：仅 AUDITOR */
export function chainVerify(fromId, toId) {
  return request.post('/admin/audit/chain-verify', {
    fromId: fromId ?? undefined, toId: toId ?? undefined
  })
}

// ===== 管理用户与角色（仅 ADMIN） =====
export function pageUsers(params) {
  return request.post('/admin/users/page', { ...params })
}
export function createUser(data) {
  return request.post('/admin/users/create', data)
}
export function updateUser(id, data) {
  return request.post('/admin/users/update', { id, ...data })
}

// ===== 角色配置 =====
/** 角色列表（角色档案 + 账号数/已授权数） */
export function listRoles() {
  return request.post('/admin/roles/list', {})
}
/** 当前登录人角色与生效权限 */
export function getMyPerms() {
  return request.post('/admin/roles/my-perms', {})
}
/** 角色权限详情：已配置权限点 + 全量目录 */
export function getRolePerms(roleCode) {
  return request.post('/admin/roles/role-perms', { roleCode })
}
/** 更新角色档案（名称/职责/排序/启停；编码不可改） */
export function updateRole(roleCode, data) {
  return request.post('/admin/roles/update', { roleCode, ...data })
}
/** 保存角色权限（菜单+按钮，整表替换） */
export function saveRolePerms(roleCode, perms) {
  return request.post('/admin/roles/save-perms', { roleCode, perms })
}

// ===== 证书模板 =====
export function pageTemplates(params) {
  return request.post('/admin/templates/page', { ...params })
}
export function getTemplate(id) {
  return request.post('/admin/templates/detail', { id })
}
export function createTemplate(data) {
  return request.post('/admin/templates/create', data)
}
export function updateTemplate(id, data) {
  return request.post('/admin/templates/update', { id, ...data })
}
export function deleteTemplate(id) {
  return request.post('/admin/templates/delete', { id })
}

// ===== RA 申请-审核-签发 =====
export function pageApplications(params) {
  return request.post('/admin/applications/page', { ...params })
}
/** 申请单详情（待办聚合页深链直达用） */
export function detailApplication(id) {
  return request.post('/admin/applications/detail', { id })
}
export function submitApplication(data, companyUscc) {
  return request.post('/admin/applications/submit', { ...data, companyUscc })
}
export function approveApplication(id, comment, companyUscc) {
  return request.post('/admin/applications/approve', { id, comment, companyUscc })
}
export function rejectApplication(id, comment, companyUscc) {
  return request.post('/admin/applications/reject', { id, comment, companyUscc })
}
export function issueApplication(id, companyUscc) {
  return request.post('/admin/applications/issue', { id, companyUscc })
}
export function cancelApplication(id, companyUscc) {
  return request.post('/admin/applications/cancel', { id, companyUscc })
}

// ===== 申请单：审批人名单 / 轨迹 / 催办 =====
/** 本公司审批人名单（提交表单下拉） */
export function listApprovers() {
  return request.post('/admin/applications/approvers', {})
}
/** 流程首个审批节点候选人（提交表单选人用） */
export function listFlowCandidates(flowCode) {
  return request.post('/admin/workflow/candidates', { flowCode })
}
/** 单据审批轨迹（按步时间正序） */
export function listTrail(id) {
  return request.post('/admin/applications/trail', { id })
}
/** 催办被指派审批人 */
export function urgeApplication(id, companyUscc) {
  return request.post('/admin/applications/urge', { id, companyUscc })
}

// ===== 站内信 / 消息中心 =====
/** 我的消息分页：{pageNo,pageSize} */
export function pageMyMessages(params) {
  return request.post('/admin/messages/page', { ...params })
}
/** 未读消息数 */
export function unreadMessageCount() {
  return request.post('/admin/messages/unread-count', {})
}
/** 标记已读：ids 数组，空=全部已读 */
export function markMessagesRead(ids) {
  return request.post('/admin/messages/read', ids)
}

// ===== 证书操作审批单（吊销/挂起/解除挂起双人复核） =====
export function submitCertOp(data, companyUscc) {
  return request.post('/admin/cert-ops/submit', { ...data, companyUscc })
}
export function pageCertOps(params) {
  return request.post('/admin/cert-ops/page', { ...params })
}
export function approveCertOp(id, comment, companyUscc) {
  return request.post('/admin/cert-ops/approve', { id, comment, companyUscc })
}
export function rejectCertOp(id, comment, companyUscc) {
  return request.post('/admin/cert-ops/reject', { id, comment, companyUscc })
}
export function cancelCertOp(id, companyUscc) {
  return request.post('/admin/cert-ops/cancel', { id, companyUscc })
}
/** 审批轨迹 */
export function listCertOpTrail(id) {
  return request.post('/admin/cert-ops/trail', { id })
}
/** 催办（仅提交人可催办） */
export function urgeCertOp(id, companyUscc) {
  return request.post('/admin/cert-ops/urge', { id, companyUscc })
}
/** 操作单详情（待办聚合页深链直达用） */
export function detailCertOp(id) {
  return request.post('/admin/cert-ops/detail', { id })
}

// ===== 系统接入（每系统一套共享凭证）管理 =====
/** 系统接入凭证列表（不返回 secret；按公司域自行过滤）；仅 apiclient:manage */
export function listApiClients() {
  return request.post('/admin/api-clients/list', {})
}
/** 登记系统接入凭证：{companyUscc, systemName, clientName?, ipAllowlist?, clientCertSha256?}（clientName 留空用系统名） */
export function createApiClient(data) {
  return request.post('/admin/api-clients/create', data)
}

// ===== 公司下的系统（证书域单元，系统名 = 主体 O） =====
/** 按公司列出系统：{companyUscc} */
export function listSystems(companyUscc) {
  return request.post('/admin/systems/list', {companyUscc})
}
/** 新增系统：{companyUscc, systemName, description?}（公司内系统名唯一） */
export function createSystem(data) {
  return request.post('/admin/systems/create', data)
}
/** 轮换接入密钥：{id} → 一次性新 secret */
export function rotateApiClient(id) {
  return request.post('/admin/api-clients/rotate', { id })
}
/** 启停系统接入凭证：{id, enabled} */
export function setApiClientStatus(id, enabled) {
  return request.post('/admin/api-clients/status', { id, enabled })
}

// ===== 导出 =====
/** 导出 PKCS#12：口令放 POST body（避免 URL 泄漏），返回二进制 blob */
export function exportCertP12(id, password) {
  return request.post('/admin/certs/export-p12', { id, password }, { responseType: 'blob' })
}
export function exportAuditCsv(companyUscc) {
  return request.post('/admin/audit/export', { companyUscc })
}

// ===== TSA 可信时间戳（公开） =====
export function getTsaPublicKey() {
  return request.post('/tsa/public-key')
}

// ===== 公开验证端点（按公司域，无需登录；PKI 协议保留路径段） =====
/** 证书在线验证三态：POST /ca/public/{companyUscc}/verify，body {certPem, purpose} */
export function verifyCertPublic(companyUscc, data) {
  return request.post(`/ca/public/${companyUscc}/verify`, data)
}
