/**
 * 机构域会话状态（路线A·多机构）。
 * - localStorage.orgKey：登录用户归属机构（空串=全局用户，非空=机构用户，登录响应写入）
 * - localStorage.caDomain：全局用户当前选择的机构域（默认 default；机构用户固定为本机构）
 */

export function isGlobalUser() {
  return !localStorage.getItem('orgKey')
}

/** 登录用户归属机构（空串=全局用户） */
export function userOrgKey() {
  return localStorage.getItem('orgKey') || ''
}

/** 全局用户选择的机构域 */
export function getDomain() {
  return localStorage.getItem('caDomain') || 'default'
}

export function setDomain(domain) {
  localStorage.setItem('caDomain', domain || 'default')
}

/**
 * 当前机构域：机构用户恒为本机构；全局用户为顶栏选择的域（缺省 default）。
 * request 封装对 /admin 全域业务接口自动附加该值。
 */
export function currentOrg() {
  return isGlobalUser() ? getDomain() : userOrgKey()
}

/** 登录时写入会话归属：orgKey 为 null 视为全局用户 */
export function saveSessionOrg(orgKey) {
  localStorage.setItem('orgKey', orgKey || '')
  // 全局用户缺省回落 default 域；机构用户固定本域
  setDomain(orgKey || 'default')
}

/** 退出/会话失效清理机构域状态 */
export function clearOrgState() {
  localStorage.removeItem('orgKey')
  localStorage.removeItem('caDomain')
}
