import { createRouter, createWebHistory } from 'vue-router'

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
        meta: { title: '概览' }
      },
      {
        path: 'root-ca',
        name: 'root-ca',
        component: () => import('@/views/RootCa.vue'),
        meta: { title: '根CA管理', roles: ['ADMIN', 'KEY_ADMIN'] }
      },
      {
        path: 'certs',
        name: 'certs',
        component: () => import('@/views/Certs.vue'),
        meta: { title: '证书管理', roles: ['ADMIN', 'OPERATOR'] }
      },
      {
        path: 'crl',
        name: 'crl',
        component: () => import('@/views/Crl.vue'),
        meta: { title: 'CRL吊销' }
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/Audit.vue'),
        meta: { title: '审计日志', roles: ['AUDITOR'] }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/Users.vue'),
        meta: { title: '用户与角色', roles: ['ADMIN'] }
      },
      {
        path: 'templates',
        name: 'templates',
        component: () => import('@/views/Templates.vue'),
        meta: { title: '证书模板', roles: ['ADMIN', 'SECURITY_ADMIN'] }
      },
      {
        path: 'applications',
        name: 'applications',
        component: () => import('@/views/Applications.vue'),
        meta: { title: 'RA申请审核', roles: ['ADMIN', 'OPERATOR', 'APPROVER'] }
      },
      {
        path: 'tsa',
        name: 'tsa',
        component: () => import('@/views/Tsa.vue'),
        meta: { title: '时间戳TSA' }
      },
      {
        path: 'ocsp',
        name: 'ocsp',
        component: () => import('@/views/Ocsp.vue'),
        meta: { title: 'OCSP查询' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
