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
        meta: { title: '根CA管理' }
      },
      {
        path: 'certs',
        name: 'certs',
        component: () => import('@/views/Certs.vue'),
        meta: { title: '证书管理' }
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
        meta: { title: '审计日志' }
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
