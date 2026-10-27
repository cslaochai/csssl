import router from './index'

// 登录守卫：未登录一律回登录页；已登录访问登录页则回概览；
// 已登录但无角色权限访问受控页面（meta.roles）则回概览
router.beforeEach(to => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.path === '/login' && token) {
    return '/dashboard'
  }
  const roles = to.meta.roles
  if (roles && token) {
    const role = localStorage.getItem('role')
    if (!roles.includes(role)) {
      return '/dashboard'
    }
  }
  return true
})
