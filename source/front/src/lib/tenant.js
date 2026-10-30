/**
 * 租户域会话状态（路线A·多租户）。
 * - localStorage.tenantKey：登录用户归属租户（空串=全局用户，非空=租户用户，登录响应写入）
 * - localStorage.primaryTenant：平台主租户（登录响应下发；登录后与无归属入口的缺省租户）
 * - localStorage.caDomain：全局用户当前工作租户（顶栏可切换；租户用户固定本租户不读此键）
 * caDomain 必须是租户表里的合法标识，非法存量值（如已删除的 default）由 syncPrimaryTenant/resolveDomain 清除。
 */

export function isGlobalUser() {
  return !localStorage.getItem('tenantKey')
}

/** 登录用户归属租户（空串=全局用户） */
export function userTenantKey() {
  return localStorage.getItem('tenantKey') || ''
}

/** 平台主租户（无租户参数的入口由后端按在营租户校准，这里仅作前端缺省） */
export function primaryTenant() {
  return localStorage.getItem('primaryTenant') || ''
}

/**
 * 当前租户域：租户用户恒为本租户；全局用户为顶栏选择的工作租户（缺省=平台主租户）。
 * request 封装对 /admin 全域业务接口自动附加该值；空则由后端按主租户兜底。
 */
export function currentTenant() {
  return isGlobalUser() ? (localStorage.getItem('caDomain') || primaryTenant()) : userTenantKey()
}

/** 全局用户顶栏切换工作租户 */
export function setDomain(domain) {
  localStorage.setItem('caDomain', domain || '')
}

/** 登录时写入会话归属：tenantKey 为 null 视为全局用户，primaryTenant 为后端下发的平台主租户 */
export function saveSessionTenant(tenantKey, tenantPrimary) {
  localStorage.setItem('tenantKey', tenantKey || '')
  if (tenantPrimary) localStorage.setItem('primaryTenant', tenantPrimary)
  setDomain(tenantKey || primaryTenant())
}

/**
 * 旧会话自愈：primaryTenant 下发机制上线前的存量会话没有该键，按在营租户补齐；
 * 顺带清掉租户表里已不存在的非法 caDomain（如已删除的 default）。
 * 返回补齐/校正后的当前租户。
 */
export function syncPrimaryTenant(tenants) {
  if (!localStorage.getItem('primaryTenant')) {
    const active = (tenants || []).find(o => o.status === 'ACTIVE') || (tenants || [])[0]
    if (active?.tenantKey) localStorage.setItem('primaryTenant', active.tenantKey)
  }
  return resolveDomain(tenants)
}

/** 校正 caDomain：不在租户清单内的存量值（如 default）清除并回落主租户 */
export function resolveDomain(tenants) {
  const stored = localStorage.getItem('caDomain')
  if (stored && (tenants || []).some(o => o.tenantKey === stored)) return stored
  if (stored) localStorage.removeItem('caDomain')
  return primaryTenant()
}

/** 退出/会话失效清理租户域状态（primaryTenant 属平台配置，登录时重写，无需清理） */
export function clearTenantState() {
  localStorage.removeItem('tenantKey')
  localStorage.removeItem('caDomain')
}
