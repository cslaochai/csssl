<template>
  <el-container class="layout app-shell">
    <!-- 顶部 Header：与 cslk HIS 同风格（深蓝顶栏 + logo + 面包屑 + 用户区） -->
    <el-header class="app-header">
      <div class="header-left">
        <router-link to="/" class="brand" title="返回首页" :style="brandStyle">
          <img class="brand-logo" :src="logo" alt="长沙麓康CA认证中心" />
          <span v-if="!sidebarCollapsed" class="brand-name">长沙麓康CA认证中心</span>
        </router-link>
        <button class="collapse-btn" :title="sidebarCollapsed ? '展开菜单' : '收起菜单'" @click="toggleSidebar">
          <el-icon v-if="sidebarCollapsed"><Expand /></el-icon>
          <el-icon v-else><Fold /></el-icon>
        </button>
        <nav class="breadcrumb-nav">
          <el-icon class="crumb-icon"><HomeFilled /></el-icon>
          <span class="crumb-label">长沙麓康CA认证中心</span>
          <el-icon v-if="currentTitle" class="crumb-sep"><ArrowRight /></el-icon>
          <span v-if="currentTitle" class="crumb-current">{{ currentTitle }}</span>
        </nav>
      </div>
      <div class="header-right">
        <!-- 当前机构：全局管理员可下拉切换；机构账号只读显示本机构 -->
        <el-dropdown v-if="isGlobal" trigger="click" @command="onDomainChange">
          <button class="org-chip" title="切换机构">
            <el-icon><OfficeBuilding /></el-icon>
            <span class="org-chip-label">当前机构</span>
            <span class="org-chip-name">{{ orgDisplay }}</span>
            <el-icon class="org-chip-arrow"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="o in orgOptions"
                :key="o.orgKey"
                :command="o.orgKey"
              >
                <span :style="o.orgKey === domain ? 'font-weight:600' : ''">
                  {{ o.orgKey === domain ? '✓ ' : '' }}{{ o.orgName || o.orgKey }}
                </span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <div v-else class="org-chip org-chip-readonly">
          <el-icon><OfficeBuilding /></el-icon>
          <span class="org-chip-label">本机构</span>
          <span class="org-chip-name">{{ orgDisplay }}</span>
        </div>

        <div class="header-divider"></div>
        <div class="header-clock">
          <span class="clock-time">{{ currentTime }}</span>
          <span class="clock-week">{{ currentWeekday }}</span>
        </div>
        <div class="header-divider"></div>
        <div class="header-user">
          <span class="header-user-name">{{ username }}</span>
          <span class="header-user-role">{{ roleText }}</span>
        </div>
        <div class="header-divider"></div>
        <el-dropdown trigger="click" @command="onCommand">
          <button class="header-icon-btn" title="设置">
            <el-icon><Setting /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="changePassword">
                <el-icon><Key /></el-icon>修改口令
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <!-- 下方：左侧导航 + 主内容 -->
    <el-container class="app-body">
      <el-aside :width="sidebarCollapsed ? '64px' : '264px'" class="app-sidebar">
        <el-scrollbar class="flex-1">
          <el-menu
            :collapse="sidebarCollapsed"
            :collapse-transition="false"
            :default-active="activeMenu"
            :default-openeds="openedGroups"
            background-color="#0a3d6b"
            text-color="#c8d0e4"
            active-text-color="#ffffff"
            router
            class="app-menu !border-none"
          >
            <template v-for="entry in menuTree" :key="entry.key || entry.path">
              <el-menu-item v-if="entry.type === 'item'" :index="entry.path">
                <el-icon><component :is="entry.icon" /></el-icon>
                <span>{{ entry.title }}</span>
              </el-menu-item>
              <el-sub-menu v-else :index="entry.key">
                <template #title>
                  <el-icon><component :is="entry.icon" /></el-icon>
                  <span>{{ entry.title }}</span>
                </template>
                <el-menu-item v-for="child in entry.children" :key="child.path" :index="child.path">
                  <el-icon><component :is="child.icon" /></el-icon>
                  <span>{{ child.title }}</span>
                </el-menu-item>
              </el-sub-menu>
            </template>
          </el-menu>
        </el-scrollbar>
      </el-aside>

      <el-main class="app-main">
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
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { logout, changePassword, listOrgsCached, getMyPerms } from '@/api/system'
import { encryptPassword, resetPublicKeyCache as resetSm2Cache } from '@/lib/password'
import { isGlobalUser, currentOrg, setDomain, syncPrimaryOrg, clearOrgState } from '@/lib/org'
import { hasPerm, permReady, loadRoleName, savePerms, saveRoleName, clearPerms } from '@/lib/perm'
import logo from '@/assets/logo.svg'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta.title || '长沙麓康CA认证中心')
const username = computed(() => localStorage.getItem('username') || '')
// 不做角色缺省回退：role 丢失由守卫按未登录处理（清会话跳登录页）
const role = computed(() => localStorage.getItem('role') || '')
// 角色名称以角色配置页维护的档案为准（登录/刷新时落 localStorage），编码仅作兜底
const roleName = ref(loadRoleName())
const roleText = computed(() => roleName.value || role.value)
// 权限版本号：刷新权限后自增，驱动侧栏重算（localStorage 本身非响应式）
const permVersion = ref(0)

// ========== 侧栏折叠（与 HIS 一致：按钮在顶栏，侧栏收成图标） ==========
const sidebarCollapsed = ref(false)
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

// 顶栏品牌区宽度与左侧菜单栏对齐（抄 HIS）：
// 展开 240px + 顶栏 24px 左内边距 = 侧栏 264px，右边缘对齐并带竖线；
// 折叠时 marginLeft -24px + 宽 64px，右边缘对齐侧栏折叠态 64px。
const brandStyle = computed(() =>
  sidebarCollapsed.value
    ? { width: '64px', marginLeft: '-24px', justifyContent: 'center' }
    : { width: '240px' }
)

// ========== 实时时钟 ==========
const currentTime = ref('')
const currentWeekday = ref('')
const dayNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
let clockTimer = null

const updateClock = () => {
  const now = new Date()
  const y = now.getFullYear()
  const mon = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  const hh = String(now.getHours()).padStart(2, '0')
  const mm = String(now.getMinutes()).padStart(2, '0')
  const ss = String(now.getSeconds()).padStart(2, '0')
  currentTime.value = `${y}-${mon}-${d} ${hh}:${mm}:${ss}`
  currentWeekday.value = dayNames[now.getDay()]
}

// ===== 当前机构（机构用户锁定本机构；全局用户经下拉切换工作机构，缺省=平台主机构） =====
const isGlobal = ref(isGlobalUser())
const domain = ref(currentOrg())
// 参与 router-view key：切换机构后当前页组件重建、数据重载
const domainFlag = ref(currentOrg())
const orgOptions = ref([])
const orgName = ref('')
const orgDisplay = computed(() => orgName.value || domain.value || '未配置机构')
const orgTitle = computed(() => domain.value ? `${orgDisplay.value}（${domain.value}）` : '尚未登记机构')

/** 下拉切换工作机构：各页数据域请求按 currentOrg() 自动附带，router-view 以 path 重建不感知域 */
function onDomainChange(v) {
  if (v === domain.value) return
  setDomain(v)
  domain.value = v
  domainFlag.value = v
  orgName.value = orgOptions.value.find(o => o.orgKey === v)?.orgName || ''
  ElMessage.success(`已切换到 ${orgName.value || v}`)
}

onMounted(async () => {
  updateClock()
  clockTimer = setInterval(updateClock, 1000)
  if (isGlobal.value) {
    const orgs = await listOrgsCached()
    orgOptions.value = orgs
    // 旧会话自愈：补齐 primaryOrg 并清掉机构表里已不存在的非法域（如已删除的 default）
    const healed = syncPrimaryOrg(orgs)
    if (healed && healed !== domain.value) {
      domain.value = healed
      domainFlag.value = healed
      orgName.value = orgs.find(o => o.orgKey === healed)?.orgName || ''
    }
  }
  if (domain.value && !orgName.value) {
    orgName.value = orgOptions.value.find(o => o.orgKey === domain.value)?.orgName || ''
  }
  window.addEventListener('perms-updated', onPermsUpdated)
  await refreshMyPerms()
})

/** 角色配置页保存后广播：侧栏与顶栏角色名即时重建，无需重新登录 */
function onPermsUpdated() {
  roleName.value = loadRoleName() || role.value
  permVersion.value++
}

/** 拉取当前角色与生效权限（角色配置变更后无需重新登录；401 由拦截器统一跳登录） */
async function refreshMyPerms() {
  try {
    const res = await getMyPerms()
    savePerms(res.data?.perms || [])
    saveRoleName(res.data?.roleName || '')
    roleName.value = res.data?.roleName || role.value
    permVersion.value++
  } catch (e) {
    // 角色停用/会话失效由拦截器处理，这里不重复提示
  }
}

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
  window.removeEventListener('perms-updated', onPermsUpdated)
})

// ===== 菜单树：页面属性（标题/图标/角色）单一来源=路由 meta，此处只定义分组 =====
const GROUP_DEFS = [
  { key: 'business', title: '证书业务', icon: 'Tickets' },
  { key: 'platform', title: '平台管理', icon: 'Setting' }
]
// 默认收起全部二级分组；仅当直达/刷新在组内页面时展开该组，保证激活项可见
const openedGroups = computed(() => {
  const cur = router.options.routes.find(r => r.path === '/').children.find(c => '/' + c.path === route.path)
  const g = cur?.meta?.group
  return g && g !== 'top' && g !== 'bottom' ? [g] : []
})

function visibleChild(c) {
  // 依赖权限版本号：角色配置保存/刷新后侧栏即时重建
  void permVersion.value
  // 新会话按权限点过滤（角色配置即时生效）；旧会话（无权限缓存）回退角色 meta
  if (permReady()) {
    if (c.meta.perm && !hasPerm(c.meta.perm)) return false
  } else if (c.meta.roles && !c.meta.roles.includes(role.value)) {
    return false
  }
  if (c.meta.globalOnly && !isGlobal.value) return false
  return true
}

const menuTree = computed(() => {
  const kids = router.options.routes.find(r => r.path === '/').children.filter(visibleChild)
  const toItem = c => ({ type: 'item', path: '/' + c.path, title: c.meta.title, icon: c.meta.icon })
  const direct = g => kids.filter(c => c.meta.group === g).map(toItem)
  const groups = GROUP_DEFS.map(g => {
    const children = direct(g.key)
    return children.length ? { type: 'sub', key: g.key, title: g.title, icon: g.icon, children } : null
  }).filter(Boolean)
  return [...direct('top'), ...groups, ...direct('bottom')]
})

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
  clearPerms()
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
    ElMessage.success('口令修改成功，需使用新口令重新登录')
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
.app-shell {
  height: 100%;
}

/* ---------- 深蓝顶栏（与 HIS 一致） ---------- */
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 24px;
  background: #0b4f8a;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.1), 0 2px 10px rgba(6, 45, 84, 0.16);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  flex-shrink: 0;
  overflow: hidden;
  border-right: 1px solid rgba(255, 255, 255, 0.3);
}

.brand-logo {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #fff;
  object-fit: contain;
  flex-shrink: 0;
}

.brand-name {
  font-size: 18px;
  font-weight: 600;
  color: #fff;
  letter-spacing: 1px;
  white-space: nowrap;
}

.collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 255, 255, 0.75);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.collapse-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.breadcrumb-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  color: rgba(255, 255, 255, 0.78);
  font-size: 13px;
}

.crumb-icon {
  color: rgba(255, 255, 255, 0.7);
}

.crumb-sep {
  color: rgba(255, 255, 255, 0.5);
}

.crumb-current {
  color: #fff;
  font-weight: 500;
}

.header-right {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.header-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 255, 255, 0.75);
  font-size: 18px;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.header-icon-btn:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}

.header-divider {
  width: 1px;
  height: 20px;
  background: rgba(255, 255, 255, 0.3);
  margin: 0 4px;
}

.header-user {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.25;
}

.header-user-name {
  font-size: 13px;
  color: #fff;
  font-weight: 500;
}

.header-user-role {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.75);
}

/* ---------- 当前机构徽标（全局用户点击下拉切换，机构用户只读） ---------- */
.org-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  font-size: 13px;
  font-family: inherit;
  max-width: 320px;
}

button.org-chip {
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

button.org-chip:hover {
  background: rgba(255, 255, 255, 0.2);
  border-color: rgba(255, 255, 255, 0.45);
}

.org-chip-label {
  color: rgba(255, 255, 255, 0.72);
  flex-shrink: 0;
}

.org-chip-name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.org-chip-arrow {
  color: rgba(255, 255, 255, 0.8);
  flex-shrink: 0;
}

.org-chip-readonly {
  background: rgba(255, 255, 255, 0.08);
  border-style: dashed;
}

.header-clock {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #fff;
  font-size: 13px;
}

.clock-time {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.clock-week {
  color: rgba(255, 255, 255, 0.85);
}

/* ---------- 侧栏（菜单区，移除了原品牌块） ---------- */
.app-body {
  height: calc(100vh - 64px);
}

.app-sidebar {
  background: #0a3d6b;
  transition: width 0.2s ease;
}

.app-menu :deep(.el-menu) {
  border-right: none;
}

.app-menu :deep(.el-menu-item.is-active) {
  background: #2d4a7a;
}

.app-main {
  background: var(--his-bg);
  padding: 16px;
}

.app-menu :deep(.el-sub-menu__title) {
  color: #c8d0e4;
}

.app-menu :deep(.el-sub-menu .el-menu) {
  background: #0a3d6b;
}

.app-menu :deep(.el-sub-menu .el-menu-item) {
  background: #0a3d6b;
  min-width: 0;
}

.app-menu :deep(.el-sub-menu .el-menu-item:hover),
.app-menu :deep(.el-sub-menu__title:hover) {
  background: #22314f;
}

.app-menu :deep(.el-sub-menu .el-menu-item.is-active) {
  background: #22314f;
  color: #fff;
}

.app-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: #fff;
}
</style>
