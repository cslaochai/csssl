import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由表：页面标题/图标/权限点单一来源=meta。
 * perm 为权限点（角色配置页可勾选，服务端按法定上限收口）；
 * roles 为旧会话（无权限缓存）的兼容回退，与六类角色的法定菜单范围一致。
 */
const ALL_ROLES = ['ADMIN', 'OPERATOR', 'APPROVER', 'AUDITOR', 'SECURITY_ADMIN', 'KEY_ADMIN']

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '概览', perm: 'menu:dashboard', roles: ALL_ROLES, group: 'top', icon: 'DataBoard' }
      },
      {
        path: 'applications',
        name: 'applications',
        component: () => import('@/views/Applications.vue'),
        meta: {
          title: 'RA申请审核',
          perm: 'menu:applications',
          roles: ['ADMIN', 'OPERATOR', 'APPROVER'],
          group: 'business',
          icon: 'EditPen'
        }
      },
      {
        path: 'certs',
        name: 'certs',
        component: () => import('@/views/Certs.vue'),
        // 密钥管理员需在证书台账下载私钥/导出 PKCS#12，故一并纳入证书管理
        meta: { title: '证书管理', perm: 'menu:certs', roles: ['ADMIN', 'OPERATOR', 'KEY_ADMIN'], group: 'business', icon: 'Document' }
      },
      {
        path: 'crl',
        name: 'crl',
        component: () => import('@/views/Crl.vue'),
        meta: { title: 'CRL吊销', perm: 'menu:crl', roles: ALL_ROLES, group: 'business', icon: 'CircleClose' }
      },
      {
        path: 'ocsp',
        name: 'ocsp',
        component: () => import('@/views/Ocsp.vue'),
        meta: { title: 'OCSP查询', perm: 'menu:ocsp', roles: ALL_ROLES, group: 'business', icon: 'Search' }
      },
      {
        path: 'tsa',
        name: 'tsa',
        component: () => import('@/views/Tsa.vue'),
        meta: { title: '时间戳TSA', perm: 'menu:tsa', roles: ALL_ROLES, group: 'business', icon: 'Clock' }
      },
      {
        path: 'tenants',
        name: 'tenants',
        component: () => import('@/views/Tenants.vue'),
        // 租户管理仅全局 ADMIN：租户用户（tenantKey 非空）由 globalOnly 拦截
        meta: {
          title: '租户管理',
          perm: 'menu:tenants',
          roles: ['ADMIN'],
          globalOnly: true,
          group: 'platform',
          icon: 'OfficeBuilding'
        }
      },
      {
        path: 'root-ca',
        name: 'root-ca',
        component: () => import('@/views/RootCa.vue'),
        meta: { title: '根CA管理', perm: 'menu:root-ca', roles: ['ADMIN', 'KEY_ADMIN'], group: 'platform', icon: 'Key' }
      },
      {
        path: 'templates',
        name: 'templates',
        component: () => import('@/views/Templates.vue'),
        meta: { title: '证书模板', perm: 'menu:templates', roles: ['ADMIN', 'SECURITY_ADMIN'], group: 'platform', icon: 'Collection' }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/Users.vue'),
        meta: { title: '用户管理', perm: 'menu:users', roles: ['ADMIN'], group: 'platform', icon: 'User' }
      },
      {
        path: 'roles',
        name: 'roles',
        component: () => import('@/views/Roles.vue'),
        // 角色配置属全局管理动作：租户管理员由 globalOnly 拦截，服务端另做数据域校验
        meta: {
          title: '角色配置',
          perm: 'menu:roles',
          roles: ['ADMIN'],
          globalOnly: true,
          group: 'platform',
          icon: 'Avatar'
        }
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/Audit.vue'),
        meta: { title: '审计日志', perm: 'menu:audit', roles: ['AUDITOR'], group: 'bottom', icon: 'Memo' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
