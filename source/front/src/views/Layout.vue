<template>
  <el-container class="layout">
    <el-aside width="220px" class="sidebar">
      <div class="sidebar-logo">
        <img class="sidebar-logo-icon" :src="logo" alt="长沙麓康CA认证中心" />
        <span>长沙麓康CA认证中心</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        background-color="#1d2a4a"
        text-color="#c8d0e4"
        active-text-color="#ffffff"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <span>概览</span>
        </el-menu-item>
        <el-menu-item v-if="can('/root-ca')" index="/root-ca">
          <el-icon><Key /></el-icon>
          <span>根CA管理</span>
        </el-menu-item>
        <el-menu-item v-if="can('/certs')" index="/certs">
          <el-icon><Document /></el-icon>
          <span>证书管理</span>
        </el-menu-item>
        <el-menu-item index="/crl">
          <el-icon><CircleClose /></el-icon>
          <span>CRL吊销</span>
        </el-menu-item>
        <el-menu-item v-if="can('/audit')" index="/audit">
          <el-icon><Memo /></el-icon>
          <span>审计日志</span>
        </el-menu-item>
        <el-menu-item v-if="can('/applications')" index="/applications">
          <el-icon><EditPen /></el-icon>
          <span>RA申请审核</span>
        </el-menu-item>
        <el-menu-item v-if="can('/templates')" index="/templates">
          <el-icon><Collection /></el-icon>
          <span>证书模板</span>
        </el-menu-item>
        <el-menu-item v-if="can('/users')" index="/users">
          <el-icon><User /></el-icon>
          <span>用户与角色</span>
        </el-menu-item>
        <el-menu-item v-if="can('/orgs') && isGlobal" index="/orgs">
          <el-icon><OfficeBuilding /></el-icon>
          <span>机构管理</span>
        </el-menu-item>
        <el-menu-item index="/tsa">
          <el-icon><Clock /></el-icon>
          <span>时间戳TSA</span>
        </el-menu-item>
        <el-menu-item index="/ocsp">
          <el-icon><Search /></el-icon>
          <span>OCSP查询</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-title">{{ currentTitle }}</div>
        <div class="header-right">
          <!-- 机构域：全局用户可选域（默认 default）；机构用户只读徽标 -->
          <template v-if="isGlobal">
            <span class="domain-label">机构域</span>
            <el-select v-model="domain" size="small" style="width: 200px" @change="onDomainChange">
              <el-option label="default（默认域）" value="default" />
              <el-option
                v-for="o in orgOptions"
                :key="o.orgKey"
                :label="o.orgName ? `${o.orgKey}（${o.orgName}）` : o.orgKey"
                :value="o.orgKey"
              />
            </el-select>
          </template>
          <el-tag v-else type="warning" effect="dark" size="small">机构：{{ userOrg }}</el-tag>
          <el-dropdown @command="onCommand">
            <span class="user-box">
              <el-icon><UserFilled /></el-icon>
              <span>{{ username }}</span>
              <el-tag size="small" type="info" effect="plain">{{ roleText }}</el-tag>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="changePassword">修改口令</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <!-- 切换机构域时按域重建页面组件，当前页数据自动重载 -->
        <router-view :key="`${route.path}@${domainFlag}`" />
      </el-main>
    </el-container>

    <!-- 修改口令 -->
    <el-dialog v-model="pwdVisible" title="修改登录口令" width="440px" :close-on-click-modal="false">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="修改成功后当前会话失效，需使用新口令重新登录"
        style="margin-bottom: 14px"
      />
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="90px">
        <el-form-item label="旧口令" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="当前登录口令" />
        </el-form-item>
        <el-form-item label="新口令" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少8位，需同时包含字母和数字" />
        </el-form-item>
        <el-form-item label="确认新口令" prop="confirmPassword">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新口令" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="pwdVisible = false">取消</el-button>
          <el-button type="primary" :loading="pwdSubmitting" @click="onChangePassword">确认修改</el-button>
        </div>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { logout, changePassword, listOrgsCached } from '@/api/system'
import { encryptPassword, resetPublicKeyCache as resetSm2Cache } from '@/lib/password'
import { isGlobalUser, userOrgKey, getDomain, setDomain, currentOrg, clearOrgState } from '@/lib/org'
import logo from '@/assets/logo.svg'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta.title || '长沙麓康CA认证中心')
const username = computed(() => localStorage.getItem('username') || '')
// 不做角色缺省回退：role 丢失由守卫按未登录处理（清会话跳登录页）
const role = computed(() => localStorage.getItem('role') || '')
const roleText = computed(() =>
  ({ ADMIN: '系统管理员', OPERATOR: 'CA操作员', APPROVER: '审核员', AUDITOR: '审计员', SECURITY_ADMIN: '安全管理员', KEY_ADMIN: '密钥管理员' })[role.value] || role.value
)

// ===== 机构域状态（全局用户=顶栏选择器；机构用户=只读徽标） =====
const isGlobal = ref(isGlobalUser())
const userOrg = ref(userOrgKey())
const domain = ref(getDomain())
const orgOptions = ref([])
// 参与 router-view key：切换域后当前页组件重建、数据重载
const domainFlag = ref(currentOrg())

onMounted(async () => {
  if (isGlobal.value) {
    orgOptions.value = await listOrgsCached()
  }
})

function onDomainChange(v) {
  setDomain(v)
  domainFlag.value = v
  ElMessage.success(`已切换到机构域 ${v}`)
}

// 菜单显隐与 router/index.js 的 meta.roles 保持一致
const MENU_ROLES = {
  '/root-ca': ['ADMIN', 'KEY_ADMIN'],
  '/certs': ['ADMIN', 'OPERATOR'],
  '/crl': null, // 登录即可
  '/audit': ['AUDITOR'],
  '/applications': ['ADMIN', 'OPERATOR', 'APPROVER'],
  '/templates': ['ADMIN', 'SECURITY_ADMIN'],
  '/users': ['ADMIN'],
  '/orgs': ['ADMIN'], // 另限全局用户（模板 v-if isGlobal）
  '/tsa': null,
  '/ocsp': null
}
function can(path) {
  const roles = MENU_ROLES[path]
  if (!roles) return true
  return !!role.value && roles.includes(role.value)
}

// ===== 修改口令 =====
const pwdVisible = ref(false)
const pwdSubmitting = ref(false)
const pwdFormRef = ref()
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入旧口令', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新口令', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (!value) return callback()
        if (value.length < 8 || !/[A-Za-z]/.test(value) || !/\d/.test(value)) {
          return callback(new Error('口令至少8位，且需同时包含字母和数字'))
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新口令', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== pwdForm.newPassword) return callback(new Error('两次输入的新口令不一致'))
        callback()
      },
      trigger: 'blur'
    }
  ]
}

function clearSession() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  clearOrgState()
  resetSm2Cache()
}

async function onChangePassword() {
  await pwdFormRef.value.validate()
  pwdSubmitting.value = true
  try {
    // 与登录页同款 SM2 加密后提交
    const [oldPassword, newPassword] = await Promise.all([
      encryptPassword(pwdForm.oldPassword),
      encryptPassword(pwdForm.newPassword)
    ])
    await changePassword({ oldPassword, newPassword })
    pwdVisible.value = false
    ElMessage.success('口令修改成功，请使用新口令重新登录')
    clearSession()
    router.push('/login')
  } finally {
    pwdSubmitting.value = false
  }
}

async function onCommand(command) {
  if (command === 'changePassword') {
    Object.assign(pwdForm, { oldPassword: '', newPassword: '', confirmPassword: '' })
    pwdVisible.value = true
    return
  }
  if (command === 'logout') {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
    try {
      await logout()
    } catch (e) {
      // 令牌失效等场景忽略，照常清空本地状态
    }
    clearSession()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100%;
}
.sidebar {
  background: #1d2a4a;
}
.sidebar :deep(.el-menu) {
  border-right: none;
}
.sidebar :deep(.el-menu-item.is-active) {
  background: #2d4a7a;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  height: 60px;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #1d2a4a;
}
.header-right {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}
.domain-label {
  font-size: 13px;
  color: #606266;
}
.user-box {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #303133;
}
.main {
  background: #f0f2f5;
  padding: 16px;
}
</style>
