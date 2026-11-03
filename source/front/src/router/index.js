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
        meta: { title: '概览', perm: 'menu:dashboard', roles: ALL_ROLES, group: 'portal', icon: 'DataBoard' }
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
        path: 'root-ca',
        name: 'root-ca',
        component: () => import('@/views/RootCa.vue'),
        meta: { title: '根CA管理', perm: 'menu:root-ca', roles: ['ADMIN', 'KEY_ADMIN'], group: 'business', icon: 'Key' }
      },
      {
        path: 'companies',
        name: 'companies',
        component: () => import('@/views/Companies.vue'),
        // 公司管理：新建公司直接执行（建独立根CA+SUB_CA、发接入凭证），系统管理员只读巡检
        meta: {
          title: '公司管理',
          perm: 'menu:companies',
          roles: ['ADMIN', 'COMPANY_ADMIN', 'COMPANY_APPROVER'],
          group: 'company',
          icon: 'OfficeBuilding'
        }
      },
      {
        path: 'company-ops',
        name: 'company-ops',
        component: () => import('@/views/CompanyOps.vue'),
        // 生命周期变更单：公司管理员发起，公司审批员批准执行（发起人不得自批）
        meta: {
          title: '公司变更审批',
          perm: 'menu:company-ops',
          roles: ['ADMIN', 'COMPANY_ADMIN', 'COMPANY_APPROVER'],
          group: 'company',
          icon: 'DocumentChecked'
        }
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
        meta: { title: '用户管理', perm: 'menu:users', roles: ['ADMIN', 'USER_ADMIN'], group: 'platform', icon: 'User' }
      },
      {
        path: 'roles',
        name: 'roles',
        component: () => import('@/views/Roles.vue'),
        // 角色配置属全局管理动作：角色档案/授权归用户与角色管理员，系统管理员只读查看
        meta: {
          title: '角色配置',
          perm: 'menu:roles',
          roles: ['ADMIN', 'USER_ADMIN'],
          group: 'platform',
          icon: 'Avatar'
        }
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/Audit.vue'),
        meta: { title: '审计日志', perm: 'menu:audit', roles: ['AUDITOR', 'ADMIN'], group: 'platform', icon: 'Memo' }
      },
      {
        path: 'messages',
        name: 'messages',
        component: () => import('@/views/MessageCenter.vue'),
        // 消息待办（原消息中心更名）：全员可见本人消息；并聚合「待我审批 / 待我执行」待办
        meta: {
          title: '消息待办',
          roles: ['ADMIN', 'OPERATOR', 'APPROVER', 'AUDITOR', 'SECURITY_ADMIN', 'KEY_ADMIN', 'COMPANY_ADMIN', 'COMPANY_APPROVER', 'USER_ADMIN', 'RA_ADMIN', 'CERT_OFFICER', 'AUDIT_ADMIN'],
          group: 'portal',
          icon: 'Bell'
        }
      },
      {
        path: 'approval-center',
        name: 'approval-center',
        component: () => import('@/views/ApprovalCenter.vue'),
        // 审批中心：二级CA重建申请单的分页查询 + 审批弹框（审批时填入本人密钥份额）+ 执行重建
        meta: {
          title: '审批中心',
          perm: 'menu:approval-center',
          roles: ['ADMIN', 'KEY_ADMIN', 'COMPANY_ADMIN'],
          group: 'portal',
          icon: 'Stamp'
        }
      },
      {
        path: 'my-approvals',
        name: 'my-approvals',
        component: () => import('@/views/MyApprovals.vue'),
        // 我的待办：四条审批流统一聚合（待我处理 / 我发起的 / 已办），一屏看完，不用在四个页面间切
        meta: {
          title: '我的待办',
          perm: 'menu:my-approvals',
          roles: ['ADMIN', 'OPERATOR', 'APPROVER', 'AUDITOR', 'SECURITY_ADMIN', 'KEY_ADMIN',
            'COMPANY_ADMIN', 'COMPANY_APPROVER', 'USER_ADMIN', 'RA_ADMIN', 'CERT_OFFICER', 'AUDIT_ADMIN'],
          group: 'portal',
          icon: 'Bell'
        }
      },
      {
        path: 'workflow-designer',
        name: 'workflow-designer',
        component: () => import('@/views/WorkflowDesigner.vue'),
        // 流程设计器：Warm-Flow 拖拽式编辑器，仅系统管理员和安全管理员可用
        meta: {
          title: '流程设计器',
          roles: ['ADMIN', 'SECURITY_ADMIN'],
          group: 'platform',
          icon: 'Connection'
        }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
