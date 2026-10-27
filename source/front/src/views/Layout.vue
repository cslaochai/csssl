<template>
  <el-container class="layout">
    <el-aside width="220px" class="sidebar">
      <div class="sidebar-logo">CSSSL · CA中心</div>
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
        <el-menu-item index="/root-ca">
          <el-icon><Key /></el-icon>
          <span>根CA管理</span>
        </el-menu-item>
        <el-menu-item index="/certs">
          <el-icon><Document /></el-icon>
          <span>证书管理</span>
        </el-menu-item>
        <el-menu-item index="/crl">
          <el-icon><CircleClose /></el-icon>
          <span>CRL吊销</span>
        </el-menu-item>
        <el-menu-item index="/audit">
          <el-icon><Memo /></el-icon>
          <span>审计日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-title">{{ currentTitle }}</div>
        <el-dropdown @command="onCommand">
          <span class="user-box">
            <el-icon><UserFilled /></el-icon>
            <span>{{ username }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { logout } from '@/api/system'
import { resetPublicKeyCache as resetSm2Cache } from '@/lib/password'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)
const currentTitle = computed(() => route.meta.title || 'CA根认证中心')
const username = computed(() => localStorage.getItem('username') || 'admin')

async function onCommand(command) {
  if (command === 'logout') {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
    try {
      await logout()
    } catch (e) {
      // 令牌失效等场景忽略，照常清空本地状态
    }
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    resetPublicKeyCache()
    resetSm2Cache()
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
