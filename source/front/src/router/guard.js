import router from './index'
import { ElMessage } from 'element-plus'
import { isGlobalUser } from '@/lib/org'

// 登录守卫：未登录一律回登录页；已登录访问登录页则回概览；
// role 丢失视为未登录（清本地会话回登录页）；
// 已登录但无角色权限访问受控页面（meta.roles）则回概览并提示；
// meta.globalOnly 页面（机构管理等）机构用户不可进入
router.beforeEach(to => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.path === '/login' && token) {
    return '/dashboard'
  }
  if (token) {
    const role = localStorage.getItem('role')
    // 角色缺失缺省回退已移除：role 丢失由守卫按未登录处理
    if (!role) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      localStorage.removeItem('orgKey')
      localStorage.removeItem('caDomain')
      if (to.path !== '/login') {
        return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
      }
      return true
    }
    const roles = to.meta.roles
    if (roles && !roles.includes(role)) {
      ElMessage.warning('无权限访问该页面，已返回概览')
      return '/dashboard'
    }
    if (to.meta.globalOnly && !isGlobalUser()) {
      ElMessage.warning('机构用户不可访问该页面，已返回概览')
      return '/dashboard'
    }
  }
  return true
})
