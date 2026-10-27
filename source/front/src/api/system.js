import request from './request'

/**
 * CSSSL · CA根认证中心 API 封装（与后端 /ssl context-path 对齐）。
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

// ===== 根CA =====
export function getCaStatus() {
  return request.get('/admin/ca/status')
}
export function initCa() {
  return request.post('/admin/ca/init')
}
export function downloadRootCert() {
  return request.get('/admin/ca/root/download')
}
export function sealRootKey() {
  return request.post('/admin/ca/seal')
}
export function unsealRootKey() {
  return request.post('/admin/ca/unseal')
}
export function downloadSubCaCert() {
  return request.get('/admin/ca/subca/download')
}

// ===== 根密钥 Shamir(3,5) 门限分持（GM/T 0034 §8.2.4.3，仅 ADMIN/KEY_ADMIN） =====
export function getCaShares() {
  return request.get('/admin/ca/shares')
}
export function migrateCaShares() {
  return request.post('/admin/ca/shares/migrate')
}
export function downloadCaShare(index) {
  return request.get(`/admin/ca/shares/${index}`)
}
export function unsealCaShares(shares) {
  return request.post('/admin/ca/unseal-shares', { shares })
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
export function revokeCert(id, reason) {
  return request.post(`/admin/certs/${id}/revoke`, { reason })
}
export function downloadCertPem(id) {
  return request.get(`/admin/certs/${id}/cert.pem`)
}
export function downloadKeyPem(id) {
  return request.get(`/admin/certs/${id}/key.pem`)
}
export function getOverview() {
  return request.get('/admin/overview')
}

// ===== CRL 吊销列表 =====
export function getCrlView() {
  return request.get('/admin/crl')
}
export function downloadCrl() {
  return request.get('/admin/crl/download')
}

// ===== 审计日志 =====
export function pageAudit(params) {
  return request.get('/admin/audit', { params })
}
export function verifyAuditLog(id) {
  return request.post('/admin/audit/verify', { id })
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
export function exportCertP12(id, password) {
  return request.get(`/admin/certs/${id}/export/p12`, { params: { password }, responseType: 'blob' })
}
export function exportAuditCsv() {
  return request.get('/admin/audit/export')
}
