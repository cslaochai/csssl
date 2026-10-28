import router from './index'
import { ElMessage } from 'element-plus'
import { isGlobalUser } from '@/lib/org'
import { permReady, hasPerm } from '@/lib/perm'

function clearSession() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  localStorage.removeItem('roleName')
  localStorage.removeItem('perms')
  localStorage.removeItem('orgKey')
  localStorage.removeItem('caDomain')
}

/** 当前角色可进入的第一个页面（侧栏顺序：概览 → 业务分组 → 审计）；无任何可用菜单返回 null */
function firstAllowedPath() {
  const root = router.options.routes.find(r => r.path === '/')
  const role = localStorage.getItem('role')
  const ready = permReady()
  const allowed = (root?.children || []).filter(c => {
    if (ready) return !c.meta.perm || hasPerm(c.meta.perm)
    return !c.meta.roles || c.meta.roles.includes(role)
  })
  if (!allowed.length) return null
  const groupOrder = ['top', 'business', 'trust', 'system', 'bottom']
  allowed.sort((a, b) => groupOrder.indexOf(a.meta.group) - groupOrder.indexOf(b.meta.group))
  return '/' + allowed[0].path
}

// 登录守卫：未登录一律回登录页；已登录访问登录页则回首个可用页；
// role 丢失视为未登录（清本地会话回登录页）；
// 页面权限点（meta.perm）优先，旧会话（无权限缓存）回退 meta.roles；
// meta.globalOnly 页面（机构管理/角色配置）机构用户不可进入。
router.beforeEach(to => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  const role = localStorage.getItem('role')
  if (token && !role) {
    clearSession()
    if (to.path !== '/login') {
      return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
    }
    return true
  }
  if (to.path === '/login' && token) {
    return firstAllowedPath() || '/login'
  }
  if (token) {
    if (permReady()) {
      if (to.meta.perm && !hasPerm(to.meta.perm)) {
        const fallback = firstAllowedPath()
        if (!fallback) {
          ElMessage.warning('当前角色未配置任何可访问页面，请联系系统管理员')
          clearSession()
          return '/login'
        }
        ElMessage.warning('无权限访问该页面，已返回可用首页')
        return fallback
      }
    } else {
      // 旧会话兼容：按路由 meta.roles 判断（服务端接口仍逐项强校验）
      const roles = to.meta.roles
      if (roles && !roles.includes(role)) {
        ElMessage.warning('无权限访问该页面，已返回概览')
        return '/dashboard'
      }
    }
    if (to.meta.globalOnly && !isGlobalUser()) {
      ElMessage.warning('机构用户不可访问该页面，已返回概览')
      return '/dashboard'
    }
  }
  return true
})
