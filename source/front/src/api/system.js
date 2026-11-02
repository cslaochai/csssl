import request from './request'

/**
 * CSSSL · CA根认证中心 API 封装（与后端 /ssl context-path 对齐）。
 * 租户不是登录主体：账号一律为平台账号，租户域由调用页按操作显式传入 tenantUscc；
 * 列表类接口不传=跨租户只读，写操作不传由后端 fail-closed 拒绝。
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

// ===== 租户管理（多租户域；新建直接执行，暂停/恢复/关闭/重置密钥走变更单双人复核） =====
export function listTenants() {
  return request.get('/admin/tenants')
}
/** 新建租户（直接执行：建租户即建独立根CA+SUB_CA 并发放接入凭证；返回一次性凭证回执） */
export function createTenant(data) {
  return request.post('/admin/tenants', data)
}
/** 租户分页查询：{pageNo,pageSize,keyword,status}（keyword 命中 标识/名称/主体O） */
export function pageTenants(params) {
  return request.get('/admin/tenants/page', { params })
}
export function getTenant(tenantUscc) {
  return request.get(`/admin/tenants/${tenantUscc}`)
}

// ===== 租户变更单（暂停/恢复/关闭/重置接入密钥：发起→他人审批→批准即执行） =====
/** 变更单分页查询：{pageNo,pageSize,status,opType,keyword} */
export function pageTenantOps(params) {
  return request.get('/admin/tenant-ops', { params })
}
/** 发起变更单：{opType,uscc,reason}；新建租户不走变更单（见 createTenant） */
export function submitTenantOp(data) {
  return request.post('/admin/tenant-ops', data)
}
/** 审批批准（不得与发起人同一人；批准即执行） */
export function approveTenantOp(id, comment) {
  return request.post(`/admin/tenant-ops/${id}/approve`, { comment })
}
/** 审批驳回（意见必填） */
export function rejectTenantOp(id, comment) {
  return request.post(`/admin/tenant-ops/${id}/reject`, { comment })
}
/** 撤回（仅发起人本人、仅待审批） */
export function cancelTenantOp(id) {
  return request.post(`/admin/tenant-ops/${id}/cancel`)
}
/** 领取接入凭证回执（仅发起人本人、一次性；密钥仅返回本次响应） */
export function takeTenantOpReceipt(id) {
  return request.get(`/admin/tenant-ops/${id}/receipt`)
}

/** 租户列表模块级缓存（各页租户下拉共用；失败返回空数组不阻断页面） */
let tenantsCache = null
export async function listTenantsCached(force = false) {
  if (tenantsCache && !force) return tenantsCache
  try {
    const res = await listTenants()
    const d = res.data
    tenantsCache = Array.isArray(d) ? d : d?.records || []
  } catch (e) {
    tenantsCache = tenantsCache || []
  }
  return tenantsCache
}

/** 租户标识→中文名映射（缓存清单）：界面展示一律中文名优先，未命中回落标识 */
export async function tenantNameMap() {
  const map = {}
  for (const t of await listTenantsCached()) {
    if (t.uscc) map[t.uscc] = t.tenantName || t.uscc
  }
  return map
}

// ===== 根CA（按租户域；tenantUscc 由所在页面显式传入） =====
export function getCaStatus(tenantUscc) {
  return request.get('/admin/ca/status', { params: { tenantUscc } })
}
export function initCa(tenantUscc) {
  return request.post('/admin/ca/init', { tenantUscc })
}
export function downloadRootCert(tenantUscc) {
  return request.get('/admin/ca/root/download', { params: { tenantUscc } })
}
export function sealRootKey(tenantUscc) {
  return request.post('/admin/ca/seal', { tenantUscc })
}
export function unsealRootKey(tenantUscc) {
  return request.post('/admin/ca/unseal', { tenantUscc })
}
export function downloadSubCaCert(tenantUscc) {
  return request.get('/admin/ca/subca/download', { params: { tenantUscc } })
}

// ===== 根密钥 Shamir(3,5) 门限分持（GM/T 0034 §8.2.4.3，仅 ADMIN/KEY_ADMIN；按租户域） =====
export function getCaShares(tenantUscc) {
  return request.get('/admin/ca/shares', { params: { tenantUscc } })
}
export function migrateCaShares(tenantUscc) {
  return request.post('/admin/ca/shares/migrate', { tenantUscc })
}
export function downloadCaShare(index, tenantUscc) {
  return request.get(`/admin/ca/shares/${index}`, { params: { tenantUscc } })
}
export function unsealCaShares(shares, tenantUscc) {
  return request.post('/admin/ca/unseal-shares', { shares, tenantUscc })
}

// ===== 证书管理 =====
export function pageCerts(params) {
  return request.get('/admin/certs', { params })
}
export function getCert(id) {
  return request.get(`/admin/certs/${id}`)
}
export function issueCert(data, tenantUscc) {
  return request.post('/admin/certs', data, { params: { tenantUscc } })
}
export function issueCertByCsr(data, tenantUscc) {
  return request.post('/admin/certs/csr', data, { params: { tenantUscc } })
}
/** 公钥直签（RA 场景）：申请方自持私钥，提交 Subject DN + 公钥 PEM + 模板/CN/SAN */
export function issueCertByPublicKey(data, tenantUscc) {
  return request.post('/admin/certs/public-key', data, { params: { tenantUscc } })
}
export function downloadCertPem(id) {
  return request.get(`/admin/certs/${id}/cert.pem`)
}
export function downloadKeyPem(id) {
  return request.get(`/admin/certs/${id}/key.pem`)
}
/** 导出证书压缩包（ZIP，不加密）：内含证书链 PEM，供 HIS 等外部系统上传 zip 回填 */
export function downloadCertZip(id) {
  return request.get(`/admin/certs/${id}/export/zip`, { responseType: 'blob' })
}
/** 证书概览（按租户域）；不传 tenantUscc 时后端按平台主租户兜底 */
export function getOverview(tenantUscc) {
  return request.get('/admin/overview', { params: { tenantUscc } })
}

// ===== CRL 吊销列表（按租户域） =====
export function getCrlView(tenantUscc) {
  return request.get('/admin/crl', { params: { tenantUscc } })
}
/** 吊销记录分页查询：{pageNo,pageSize,keyword,status,reason,tenantUscc?}（不传租户=跨租户只读） */
export function pageCrlRecords(params) {
  return request.get('/admin/crl/records', { params })
}
export function downloadCrl(tenantUscc) {
  return request.get('/admin/crl/download', { params: { tenantUscc } })
}
/** 增量 CRL（dCRL）：{deltaPem, thisUpdate, nextUpdate, entryCount, crlNumber, baseCrlNumber} */
export function getDeltaCrl(tenantUscc) {
  return request.get('/admin/crl/delta', { params: { tenantUscc } })
}

// ===== 审计日志 =====
export function pageAudit(params) {
  return request.get('/admin/audit', { params })
}
export function verifyAuditLog(id) {
  return request.post('/admin/audit/verify', { id })
}
/** 哈希链对账（区间可空）：返回 {checked, broken:[{id,reason}]}，仅 AUDITOR */
export function chainVerify(fromId, toId) {
  return request.get('/admin/audit/chain-verify', {
    params: { fromId: fromId ?? undefined, toId: toId ?? undefined }
  })
}

// ===== 管理用户与角色（GM/T 0034 分权制衡，仅 ADMIN） =====
export function pageUsers(params) {
  return request.get('/admin/users', { params })
}
export function createUser(data) {
  return request.post('/admin/users', data)
}
export function updateUser(id, data) {
  return request.post(`/admin/users/${id}`, data)
}

// ===== 角色配置（GM/T 0034 法定六角色 + 菜单/按钮权限） =====
/** 角色列表（角色档案 + 账号数/已授权数；账号页角色下拉共用） */
export function listRoles() {
  return request.get('/admin/roles')
}
/** 当前登录人角色与生效权限（角色配置保存后自刷新） */
export function getMyPerms() {
  return request.get('/admin/roles/my-perms')
}
/** 角色权限详情：已配置权限点 + 全量目录（allowed=是否在法定边界内） */
export function getRolePerms(roleCode) {
  return request.get(`/admin/roles/${roleCode}/perms`)
}
/** 更新角色档案（名称/职责/排序/启停；编码不可改） */
export function updateRole(roleCode, data) {
  return request.post(`/admin/roles/${roleCode}`, data)
}
/** 保存角色权限（菜单+按钮，整表替换；越法定边界服务端拒绝） */
export function saveRolePerms(roleCode, perms) {
  return request.post(`/admin/roles/${roleCode}/perms`, { perms })
}

// ===== 证书模板（GM/T 0034 §5.3.4） =====
export function pageTemplates(params) {
  return request.get('/admin/templates', { params })
}
export function getTemplate(id) {
  return request.get(`/admin/templates/${id}`)
}
export function createTemplate(data) {
  return request.post('/admin/templates', data)
}
export function updateTemplate(id, data) {
  return request.post(`/admin/templates/${id}`, data)
}
export function deleteTemplate(id) {
  return request.post(`/admin/templates/${id}/delete`)
}

// ===== RA 申请-审核-签发（GM/T 0034 §5.2.2） =====
export function pageApplications(params) {
  return request.get('/admin/applications', { params })
}
export function submitApplication(data, tenantUscc) {
  return request.post('/admin/applications', data, { params: { tenantUscc } })
}
export function approveApplication(id, comment, tenantUscc) {
  return request.post(`/admin/applications/${id}/approve`, { comment }, { params: { tenantUscc } })
}
export function rejectApplication(id, comment, tenantUscc) {
  return request.post(`/admin/applications/${id}/reject`, { comment }, { params: { tenantUscc } })
}
export function issueApplication(id, tenantUscc) {
  return request.post(`/admin/applications/${id}/issue`, null, { params: { tenantUscc } })
}
export function cancelApplication(id, tenantUscc) {
  return request.post(`/admin/applications/${id}/cancel`, null, { params: { tenantUscc } })
}

// ===== 申请单：审批人名单 / 轨迹 / 催办 =====
/** 本租户审批人名单（提交表单下拉；不依赖 user:list） */
export function listApprovers(tenantUscc) {
  return request.get('/admin/applications/approvers', { params: { tenantUscc } })
}
/** 单据审批轨迹（按步时间正序） */
export function listTrail(id) {
  return request.get(`/admin/applications/${id}/trail`)
}
/** 催办被指派审批人 */
export function urgeApplication(id, tenantUscc) {
  return request.post(`/admin/applications/${id}/urge`, null, { params: { tenantUscc } })
}

// ===== 站内信 / 消息中心 =====
/** 我的消息分页：{pageNo,pageSize} */
export function pageMyMessages(params) {
  return request.get('/admin/messages', { params })
}
/** 未读消息数（登录后铃铛红点） */
export function unreadMessageCount() {
  return request.get('/admin/messages/unread-count')
}
/** 标记已读：ids 数组，空=全部已读 */
export function markMessagesRead(ids) {
  return request.post('/admin/messages/read', ids)
}

// ===== 证书操作审批单（吊销/挂起/解除挂起双人复核，批准即执行进 CRL） =====
export function submitCertOp(data, tenantUscc) {
  return request.post('/admin/cert-ops', data, { params: { tenantUscc } })
}
export function pageCertOps(params) {
  return request.get('/admin/cert-ops', { params })
}
export function approveCertOp(id, comment, tenantUscc) {
  return request.post(`/admin/cert-ops/${id}/approve`, { comment }, { params: { tenantUscc } })
}
export function rejectCertOp(id, comment, tenantUscc) {
  return request.post(`/admin/cert-ops/${id}/reject`, { comment }, { params: { tenantUscc } })
}
export function cancelCertOp(id, tenantUscc) {
  return request.post(`/admin/cert-ops/${id}/cancel`, null, { params: { tenantUscc } })
}

// ===== 导出（PKCS#12 软件证书 / 审计归档 CSV） =====
/** 导出 PKCS#12：口令放 POST body（避免 URL 泄漏），返回二进制 blob */
export function exportCertP12(id, password) {
  return request.post(`/admin/certs/${id}/export/p12`, { password }, { responseType: 'blob' })
}
export function exportAuditCsv(tenantUscc) {
  return request.get('/admin/audit/export', { params: { tenantUscc } })
}

// ===== TSA 可信时间戳（公开） =====
export function getTsaPublicKey() {
  return request.get('/tsa/publicKey')
}

// ===== 公开验证端点（按租户域，无需登录） =====
/** 证书在线验证三态：POST /ca/public/{tenantUscc}/verify，body {certPem, purpose} */
export function verifyCertPublic(tenantUscc, data) {
  return request.post(`/ca/public/${tenantUscc}/verify`, data)
}
