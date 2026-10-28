/**
 * 权限点缓存与判断（角色配置：法定六角色 + 菜单/按钮权限）。
 *
 * 权限来源：登录响应 data.perms（服务端已按"角色配置 ∩ 法定上限"收口）；
 * 角色配置保存后由配置页调用 refreshMyPerms() 自刷新，无需重新登录。
 *
 * 兼容口径：旧会话（localStorage 没有 perms 键）返回 null——
 * 守卫与菜单回退到路由 meta.roles 的角色判断，按钮不隐藏；
 * 服务端接口仍逐项强校验，兼容不会造成越权。
 */
const PERMS_KEY = 'perms'
const ROLE_NAME_KEY = 'roleName'

/** 读取权限点数组；未初始化（旧会话）返回 null，区别于"权限为空数组" */
export function loadPerms() {
  const raw = localStorage.getItem(PERMS_KEY)
  if (raw === null) return null
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch (e) {
    return []
  }
}

export function savePerms(list) {
  localStorage.setItem(PERMS_KEY, JSON.stringify(list || []))
}

export function clearPerms() {
  localStorage.removeItem(PERMS_KEY)
  localStorage.removeItem(ROLE_NAME_KEY)
}

export function saveRoleName(name) {
  localStorage.setItem(ROLE_NAME_KEY, name || '')
}

export function loadRoleName() {
  return localStorage.getItem(ROLE_NAME_KEY) || ''
}

/** 权限是否就绪（新会话登录后恒为 true） */
export function permReady() {
  return loadPerms() !== null
}

/**
 * 是否具备权限点：支持单个编码或编码数组（任一命中即可）。
 * 未登录/旧会话（无权限缓存）时不拦截，避免误伤存量会话。
 */
export function hasPerm(codes) {
  if (!codes || (Array.isArray(codes) && codes.length === 0)) return true
  const perms = loadPerms()
  if (perms === null) return true
  const list = Array.isArray(codes) ? codes : [codes]
  return list.some(code => perms.includes(code))
}
