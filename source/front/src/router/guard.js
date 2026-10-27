import router from './index'

// 登录守卫：未登录一律回登录页；已登录访问登录页则回概览
router.beforeEach(to => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    return { path: '/login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.path === '/login' && token) {
    return '/dashboard'
  }
  return true
})
