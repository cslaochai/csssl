/**
 * 机构域会话状态（路线A·多机构）。
 * - localStorage.orgKey：登录用户归属机构（空串=全局用户，非空=机构用户，登录响应写入）
 * - localStorage.primaryOrg：平台主机构（登录响应下发；登录后与无归属入口的缺省机构）
 * - localStorage.caDomain：全局用户当前工作机构（顶栏可切换；机构用户固定本机构不读此键）
 * caDomain 必须是机构表里的合法标识，非法存量值（如已删除的 default）由 syncPrimaryOrg/resolveDomain 清除。
 */

export function isGlobalUser() {
  return !localStorage.getItem('orgKey')
}

/** 登录用户归属机构（空串=全局用户） */
export function userOrgKey() {
  return localStorage.getItem('orgKey') || ''
}

/** 平台主机构（无机构参数的入口由后端按在营机构校准，这里仅作前端缺省） */
export function primaryOrg() {
  return localStorage.getItem('primaryOrg') || ''
}

/**
 * 当前机构域：机构用户恒为本机构；全局用户为顶栏选择的工作机构（缺省=平台主机构）。
 * request 封装对 /admin 全域业务接口自动附加该值；空则由后端按主机构兜底。
 */
export function currentOrg() {
  return isGlobalUser() ? (localStorage.getItem('caDomain') || primaryOrg()) : userOrgKey()
}

/** 全局用户顶栏切换工作机构 */
export function setDomain(domain) {
  localStorage.setItem('caDomain', domain || '')
}

/** 登录时写入会话归属：orgKey 为 null 视为全局用户，primaryOrg 为后端下发的平台主机构 */
export function saveSessionOrg(orgKey, orgPrimary) {
  localStorage.setItem('orgKey', orgKey || '')
  if (orgPrimary) localStorage.setItem('primaryOrg', orgPrimary)
  setDomain(orgKey || primaryOrg())
}

/**
 * 旧会话自愈：primaryOrg 下发机制上线前的存量会话没有该键，按在营机构补齐；
 * 顺带清掉机构表里已不存在的非法 caDomain（如已删除的 default）。
 * 返回补齐/校正后的当前机构。
 */
export function syncPrimaryOrg(orgs) {
  if (!localStorage.getItem('primaryOrg')) {
    const active = (orgs || []).find(o => o.status === 'ACTIVE') || (orgs || [])[0]
    if (active?.orgKey) localStorage.setItem('primaryOrg', active.orgKey)
  }
  return resolveDomain(orgs)
}

/** 校正 caDomain：不在机构清单内的存量值（如 default）清除并回落主机构 */
export function resolveDomain(orgs) {
  const stored = localStorage.getItem('caDomain')
  if (stored && (orgs || []).some(o => o.orgKey === stored)) return stored
  if (stored) localStorage.removeItem('caDomain')
  return primaryOrg()
}

/** 退出/会话失效清理机构域状态（primaryOrg 属平台配置，登录时重写，无需清理） */
export function clearOrgState() {
  localStorage.removeItem('orgKey')
  localStorage.removeItem('caDomain')
}
