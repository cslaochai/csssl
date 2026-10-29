import request from './request'
import { currentOrg } from '@/lib/org'

/**
 * CSSSL · CA根认证中心 API 封装（与后端 /ssl context-path 对齐）。
 * 多机构（路线A）：/admin 全域业务接口的机构域参数由 request 拦截器按 currentOrg() 自动附加；
 * 根CA/CRL 等按域操作在本文件中显式带 orgKey（缺省取当前域）。
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

// ===== 机构管理（多机构域，仅全局 ADMIN） =====
export function listOrgs() {
  return request.get('/admin/orgs')
}
/** 机构分页查询：{pageNo,pageSize,keyword,status}（keyword 命中 标识/名称/主体O） */
export function pageOrgs(params) {
  return request.get('/admin/orgs/page', { params })
}
export function getOrg(orgKey) {
  return request.get(`/admin/orgs/${orgKey}`)
}
/** 创建即生成该机构独立根CA+SUB_CA，可能耗时数秒 */
export function createOrg(data) {
  return request.post('/admin/orgs', data)
}
export function suspendOrg(orgKey) {
  return request.post(`/admin/orgs/${orgKey}/suspend`)
}
export function resumeOrg(orgKey) {
  return request.post(`/admin/orgs/${orgKey}/resume`)
}
export function closeOrg(orgKey) {
  return request.post(`/admin/orgs/${orgKey}/close`)
}

/** 机构列表模块级缓存（顶栏/各页机构下拉共用；失败返回空数组不阻断页面） */
let orgsCache = null
export async function listOrgsCached(force = false) {
  if (orgsCache && !force) return orgsCache
  try {
    const res = await listOrgs()
    const d = res.data
    orgsCache = Array.isArray(d) ? d : d?.records || []
  } catch (e) {
    orgsCache = orgsCache || []
  }
  return orgsCache
}

// ===== 根CA（按机构域） =====
export function getCaStatus(orgKey = currentOrg()) {
  return request.get('/admin/ca/status', { params: { orgKey } })
}
export function initCa(orgKey = currentOrg()) {
  return request.post('/admin/ca/init', { orgKey })
}
export function downloadRootCert(orgKey = currentOrg()) {
  return request.get('/admin/ca/root/download', { params: { orgKey } })
}
export function sealRootKey(orgKey = currentOrg()) {
  return request.post('/admin/ca/seal', { orgKey })
}
export function unsealRootKey(orgKey = currentOrg()) {
  return request.post('/admin/ca/unseal', { orgKey })
}
export function downloadSubCaCert(orgKey = currentOrg()) {
  return request.get('/admin/ca/subca/download', { params: { orgKey } })
}

// ===== 根密钥 Shamir(3,5) 门限分持（GM/T 0034 §8.2.4.3，仅 ADMIN/KEY_ADMIN；按机构域） =====
export function getCaShares(orgKey = currentOrg()) {
  return request.get('/admin/ca/shares', { params: { orgKey } })
}
export function migrateCaShares(orgKey = currentOrg()) {
  return request.post('/admin/ca/shares/migrate', { orgKey })
}
export function downloadCaShare(index, orgKey = currentOrg()) {
  return request.get(`/admin/ca/shares/${index}`, { params: { orgKey } })
}
export function unsealCaShares(shares, orgKey = currentOrg()) {
  return request.post('/admin/ca/unseal-shares', { shares, orgKey })
}

// ===== 证书管理 =====
export function pageCerts(params) {
  return request.get('/admin/certs', { params })
}
export function getCert(id) {
  return request.get(`/admin/certs/${id}`)
}
export function issueCert(data) {
  return request.post('/admin/certs', data)
}
export function issueCertByCsr(data) {
  return request.post('/admin/certs/csr', data)
}
/** 公钥直签（RA 场景）：申请方自持私钥，提交 Subject DN + 公钥 PEM + 模板/CN/SAN */
export function issueCertByPublicKey(data) {
  return request.post('/admin/certs/public-key', data)
}
export function revokeCert(id, reason) {
  return request.post(`/admin/certs/${id}/revoke`, { reason })
}
export function downloadCertPem(id) {
  return request.get(`/admin/certs/${id}/cert.pem`)
}
export function downloadKeyPem(id) {
  return request.get(`/admin/certs/${id}/key.pem`)
}
/** 证书概览（按机构域）；全局用户可传指定域，机构用户由后端锁本域 */
export function getOverview(orgKey = currentOrg()) {
  return request.get('/admin/overview', { params: { orgKey } })
}

// ===== CRL 吊销列表（按机构域） =====
export function getCrlView(orgKey = currentOrg()) {
  return request.get('/admin/crl', { params: { orgKey } })
}
/** 吊销记录分页查询：{pageNo,pageSize,keyword,status,reason}（orgKey 由请求层自动注入） */
export function pageCrlRecords(params) {
  return request.get('/admin/crl/records', { params })
}
export function downloadCrl(orgKey = currentOrg()) {
  return request.get('/admin/crl/download', { params: { orgKey } })
}
/** 增量 CRL（dCRL）：{deltaPem, thisUpdate, nextUpdate, entryCount, crlNumber, baseCrlNumber} */
export function getDeltaCrl(orgKey = currentOrg()) {
  return request.get('/admin/crl/delta', { params: { orgKey } })
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
export function submitApplication(data) {
  return request.post('/admin/applications', data)
}
export function approveApplication(id, comment) {
  return request.post(`/admin/applications/${id}/approve`, { comment })
}
export function rejectApplication(id, comment) {
  return request.post(`/admin/applications/${id}/reject`, { comment })
}
export function issueApplication(id) {
  return request.post(`/admin/applications/${id}/issue`)
}
export function cancelApplication(id) {
  return request.post(`/admin/applications/${id}/cancel`)
}

// ===== 证书生命周期扩展（GM/T 0034 §12.6/12.7/12.2） =====
export function suspendCert(id, reason) {
  return request.post(`/admin/certs/${id}/suspend`, { reason })
}
export function unsuspendCert(id) {
  return request.post(`/admin/certs/${id}/unsuspend`)
}
export function renewCert(id, validityDays) {
  return request.post(`/admin/certs/${id}/renew`, { validityDays })
}
export function rekeyCert(id, validityDays) {
  return request.post(`/admin/certs/${id}/rekey`, { validityDays })
}

// ===== 导出（PKCS#12 软件证书 / 审计归档 CSV） =====
/** 导出 PKCS#12：口令放 POST body（避免 URL 泄漏），返回二进制 blob */
export function exportCertP12(id, password) {
  return request.post(`/admin/certs/${id}/export/p12`, { password }, { responseType: 'blob' })
}
export function exportAuditCsv() {
  return request.get('/admin/audit/export')
}

// ===== TSA 可信时间戳（公开） =====
export function getTsaPublicKey() {
  return request.get('/tsa/publicKey')
}

// ===== 公开验证端点（按机构域，无需登录） =====
/** 证书在线验证三态：POST /ca/public/{orgKey}/verify，body {certPem, purpose} */
export function verifyCertPublic(orgKey, data) {
  return request.post(`/ca/public/${orgKey}/verify`, data)
}
