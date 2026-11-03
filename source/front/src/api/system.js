import request from './request'

/**
 * CSSSL · CA根认证中心 API 封装（与后端 /ssl context-path 对齐）。
 * 公司不是登录主体：账号一律为平台账号，公司域由调用页按操作显式传入 companyUscc；
 * 列表类接口不传=跨公司只读，写操作不传由后端 fail-closed 拒绝。
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

// ===== 公司管理（多公司域；新建直接执行，暂停/恢复/关闭/重置密钥走变更单双人复核） =====
export function listCompanies() {
  return request.get('/admin/companies')
}
/** 新建公司（直接执行：建公司即建独立根CA+SUB_CA 并发放接入凭证；返回一次性凭证回执） */
export function createCompany(data) {
  return request.post('/admin/companies', data)
}
/** 公司分页查询：{pageNo,pageSize,keyword,status}（keyword 命中 标识/名称/主体O） */
export function pageCompanies(params) {
  return request.get('/admin/companies/page', { params })
}
export function getCompany(companyUscc) {
  return request.get(`/admin/companies/${companyUscc}`)
}

// ===== 公司变更单（暂停/恢复/关闭/重置接入密钥：发起→他人审批→批准即执行） =====
/** 变更单分页查询：{pageNo,pageSize,status,opType,keyword} */
export function pageCompanyOps(params) {
  return request.get('/admin/company-ops', { params })
}
/** 发起变更单：{opType,uscc,reason}；新建公司不走变更单（见 createCompany） */
export function submitCompanyOp(data) {
  return request.post('/admin/company-ops', data)
}
/** 审批批准（不得与发起人同一人；批准即执行） */
export function approveCompanyOp(id, comment) {
  return request.post(`/admin/company-ops/${id}/approve`, { comment })
}
/** 审批驳回（意见必填） */
export function rejectCompanyOp(id, comment) {
  return request.post(`/admin/company-ops/${id}/reject`, { comment })
}
/** 撤回（仅发起人本人、仅待审批） */
export function cancelCompanyOp(id) {
  return request.post(`/admin/company-ops/${id}/cancel`)
}
/** 原保管人投票（重新指派保管人单专用：{agree,comment}；>3 同意自动执行，2 驳回否决，不经过公司审批员） */
export function voteCompanyOp(id, data) {
  return request.post(`/admin/company-ops/${id}/vote`, data)
}
/** 领取接入凭证回执（仅发起人本人、一次性；密钥仅返回本次响应） */
export function takeCompanyOpReceipt(id) {
  return request.get(`/admin/company-ops/${id}/receipt`)
}
/** 审批轨迹（按步时间正序，面向业务用户） */
export function listCompanyOpTrail(id) {
  return request.get(`/admin/company-ops/${id}/trail`)
}
/** 催办：向被指派审批人（或重新指派保管人的原 5 名保管人）推送站内信并补 URGE 轨迹（仅提交人可催办） */
export function urgeCompanyOp(id) {
  return request.post(`/admin/company-ops/${id}/urge`)
}
/** 变更单详情（待办聚合页深链直达用） */
export function detailCompanyOp(id) {
  return request.get(`/admin/company-ops/${id}`)
}

/** 公司列表模块级缓存（各页公司下拉共用；失败返回空数组不阻断页面） */
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

// ===== 根CA（按公司域；companyUscc 由所在页面显式传入） =====
export function getCaStatus(companyUscc) {
  return request.get('/admin/ca/status', { params: { companyUscc } })
}
export function initCa(companyUscc) {
  return request.post('/admin/ca/init', { companyUscc })
}
export function downloadRootCert(companyUscc) {
  return request.get('/admin/ca/root/download', { params: { companyUscc } })
}
export function sealRootKey(companyUscc) {
  return request.post('/admin/ca/seal', { companyUscc })
}
export function unsealRootKey(companyUscc) {
  return request.post('/admin/ca/unseal', { companyUscc })
}
export function downloadSubCaCert(companyUscc) {
  return request.get('/admin/ca/subca/download', { params: { companyUscc } })
}

// ===== 根密钥 Shamir(3,5) 门限分持（GM/T 0034 §8.2.4.3，仅 ADMIN/KEY_ADMIN；按公司域） =====
export function getCaShares(companyUscc) {
  return request.get('/admin/ca/shares', { params: { companyUscc } })
}
export function migrateCaShares(companyUscc) {
  return request.post('/admin/ca/shares/migrate', { companyUscc })
}
export function downloadCaShare(index, companyUscc) {
  return request.get(`/admin/ca/shares/${index}`, { params: { companyUscc } })
}
export function unsealCaShares(shares, companyUscc) {
  return request.post('/admin/ca/unseal-shares', { shares, companyUscc })
}

// ===== 根密钥份额保管人绑定（建根/开公司时指定，一人一份；3-of-5 动用根私钥需审批） =====
/** 指定/重指定根私钥份额保管人：{companyUscc, holderUsernames[5]}（仅 ADMIN/KEY_ADMIN） */
export function assignShares(data) {
  return request.post('/admin/ca/shares/assign', data)
}
/**
 * 某公司密钥份额总览（一次调用返回全部）：
 * { holders:[{shareIndex,holderRealName}], mine:{index,total,threshold,yHex,shareJson}|null, threshold, total }
 * holders 对密钥管理员/系统管理员/公司管理员可见（只给姓名，账号不下发）；mine 仅保管人本人可见（非保管人=null）。
 */
export function getShareOverview(companyUscc) {
  return request.get('/admin/ca/shares/overview', { params: { companyUscc } })
}

// ===== 用户简易列表（保管人下拉用；不含口令；仅开公司/指派保管人权限） =====
// 传 companyUscc 则收敛为「平台级 + 该公司本域」的密钥管理员；不传则只返回平台级
export function listSimpleUsers(companyUscc) {
  return request.get('/admin/users/simple', { params: { companyUscc } })
}

// ===== 二级CA 重建申请单（发起/执行=公司管理员或安全管理员；保管人只投票并附本人份额；系统管理员只读） =====
/** 发起重建申请：{companyUscc, reason}（ca:subca:rebuild:apply；会自动通知 5 名保管人） */
export function applySubCaRebuild(data) {
  return request.post('/admin/ca/subca-rebuild/apply', data)
}
/** 申请单分页：{pageNo,pageSize,status,keyword,companyUscc}（ca:subca:rebuild:view 即可只读巡检；公司用户服务端强制锁本公司，全局用户不传=跨公司只读） */
export function pageSubCaRebuild(params) {
  return request.get('/admin/ca/subca-rebuild/page', { params })
}
/** 当前登录人作为保管人且尚未投票的待审单（轻量轮询入口） */
export function pendingSubCaRebuild() {
  return request.get('/admin/ca/subca-rebuild/pending')
}
/** 当前登录人作为发起人且已审批通过（待执行）的申请单（消息待办 → 待我执行） */
export function myExecutableSubCaRebuild() {
  return request.get('/admin/ca/subca-rebuild/mine-executable')
}
/** 申请单详情 */
export function detailSubCaRebuild(id) {
  return request.get(`/admin/ca/subca-rebuild/${id}`)
}
/** 审批同意：{comment, shareJson}（须为保管人；同意必须附上本人持有的根私钥份额；3-of-5 任意 3 人同意即批准） */
export function approveSubCaRebuild(id, data) {
  return request.post(`/admin/ca/subca-rebuild/${id}/approve`, data)
}
/** 审批驳回：{comment}（须为保管人；意见必填；任一驳回即否决） */
export function rejectSubCaRebuild(id, data) {
  return request.post(`/admin/ca/subca-rebuild/${id}/reject`, data)
}
/** 撤回申请（仅发起人本人，且仅 PENDING） */
export function cancelSubCaRebuild(id) {
  return request.post(`/admin/ca/subca-rebuild/${id}/cancel`)
}
/** 执行重建：{useCollectedShares:true} 用审批阶段已收集的保管人份额；或 {shareJsons[≥3]} 手动粘贴（ca:subca:rebuild:execute；保管人无此权限，避免既审批又执行） */
export function executeSubCaRebuild(id, data) {
  return request.post(`/admin/ca/subca-rebuild/${id}/execute`, data)
}
/** 审批轨迹（按步时间正序，与 RA 申请 / 操作单 / 变更单同一条轨迹表） */
export function listSubCaRebuildTrail(id) {
  return request.get(`/admin/ca/subca-rebuild/${id}/trail`)
}
/** 催办：向尚未投票的保管人推送站内信并补 URGE 轨迹（仅发起人本人） */
export function urgeSubCaRebuild(id) {
  return request.post(`/admin/ca/subca-rebuild/${id}/urge`)
}

// ===== 证书管理（只读台账 + 生命周期操作单；后端无直发签发接口，发证只走 RA 申请与二级CA重建申请） =====
export function pageCerts(params) {
  return request.get('/admin/certs', { params })
}
export function getCert(id) {
  return request.get(`/admin/certs/${id}`)
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
/** 证书概览（按公司域）；不传 companyUscc 时后端按平台主公司兜底 */
export function getOverview(companyUscc) {
  return request.get('/admin/overview', { params: { companyUscc } })
}

// ===== CRL 吊销列表（按公司域） =====
export function getCrlView(companyUscc) {
  return request.get('/admin/crl', { params: { companyUscc } })
}
/** 吊销记录分页查询：{pageNo,pageSize,keyword,status,reason,companyUscc?}（不传公司=跨公司只读） */
export function pageCrlRecords(params) {
  return request.get('/admin/crl/records', { params })
}
export function downloadCrl(companyUscc) {
  return request.get('/admin/crl/download', { params: { companyUscc } })
}
/** 增量 CRL（dCRL）：{deltaPem, thisUpdate, nextUpdate, entryCount, crlNumber, baseCrlNumber} */
export function getDeltaCrl(companyUscc) {
  return request.get('/admin/crl/delta', { params: { companyUscc } })
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
/** 申请单详情（待办聚合页深链直达用） */
export function detailApplication(id) {
  return request.get(`/admin/applications/${id}`)
}
export function submitApplication(data, companyUscc) {
  return request.post('/admin/applications', data, { params: { companyUscc } })
}
export function approveApplication(id, comment, companyUscc) {
  return request.post(`/admin/applications/${id}/approve`, { comment }, { params: { companyUscc } })
}
export function rejectApplication(id, comment, companyUscc) {
  return request.post(`/admin/applications/${id}/reject`, { comment }, { params: { companyUscc } })
}
export function issueApplication(id, companyUscc) {
  return request.post(`/admin/applications/${id}/issue`, null, { params: { companyUscc } })
}
export function cancelApplication(id, companyUscc) {
  return request.post(`/admin/applications/${id}/cancel`, null, { params: { companyUscc } })
}

// ===== 申请单：审批人名单 / 轨迹 / 催办 =====
/** 本公司审批人名单（提交表单下拉；不依赖 user:list） */
export function listApprovers() {
  return request.get('/admin/applications/approvers')
}
/** 单据审批轨迹（按步时间正序） */
export function listTrail(id) {
  return request.get(`/admin/applications/${id}/trail`)
}
/** 催办被指派审批人 */
export function urgeApplication(id, companyUscc) {
  return request.post(`/admin/applications/${id}/urge`, null, { params: { companyUscc } })
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
export function submitCertOp(data, companyUscc) {
  return request.post('/admin/cert-ops', data, { params: { companyUscc } })
}
export function pageCertOps(params) {
  return request.get('/admin/cert-ops', { params })
}
export function approveCertOp(id, comment, companyUscc) {
  return request.post(`/admin/cert-ops/${id}/approve`, { comment }, { params: { companyUscc } })
}
export function rejectCertOp(id, comment, companyUscc) {
  return request.post(`/admin/cert-ops/${id}/reject`, { comment }, { params: { companyUscc } })
}
export function cancelCertOp(id, companyUscc) {
  return request.post(`/admin/cert-ops/${id}/cancel`, null, { params: { companyUscc } })
}
/** 审批轨迹（按步时间正序，面向业务用户） */
export function listCertOpTrail(id) {
  return request.get(`/admin/cert-ops/${id}/trail`)
}
/** 催办：向被指派审批人推送站内信并补 URGE 轨迹（仅提交人可催办） */
export function urgeCertOp(id, companyUscc) {
  return request.post(`/admin/cert-ops/${id}/urge`, null, { params: { companyUscc } })
}
/** 操作单详情（待办聚合页深链直达用） */
export function detailCertOp(id) {
  return request.get(`/admin/cert-ops/${id}`)
}

// ===== 导出（PKCS#12 软件证书 / 审计归档 CSV） =====
/** 导出 PKCS#12：口令放 POST body（避免 URL 泄漏），返回二进制 blob */
export function exportCertP12(id, password) {
  return request.post(`/admin/certs/${id}/export/p12`, { password }, { responseType: 'blob' })
}
export function exportAuditCsv(companyUscc) {
  return request.get('/admin/audit/export', { params: { companyUscc } })
}

// ===== TSA 可信时间戳（公开） =====
export function getTsaPublicKey() {
  return request.get('/tsa/publicKey')
}

// ===== 公开验证端点（按公司域，无需登录） =====
/** 证书在线验证三态：POST /ca/public/{companyUscc}/verify，body {certPem, purpose} */
export function verifyCertPublic(companyUscc, data) {
  return request.post(`/ca/public/${companyUscc}/verify`, data)
}
