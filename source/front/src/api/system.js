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
