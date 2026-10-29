<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-title">
        <div class="login-logo">
          <img :src="logo" alt="长沙麓康CA认证中心" />
        </div>
        <h1>长沙麓康CA认证中心</h1>
        <p>证书签发 · 吊销 CRL · 国密加密传输</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="账号" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="口令"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="onSubmit">
          登 录
        </el-button>
      </el-form>
      <div class="login-tip">
        <span>如忘记口令，请联系系统管理员重置</span>
        <el-link type="primary" :underline="false" class="cred-link" @click="credVisible = true">登录凭证</el-link>
      </div>
    </div>

    <!-- 登录凭证（演示环境测试账号一览，一键填入） -->
    <el-dialog v-model="credVisible" title="登录凭证（演示账号）" width="560px">
      <el-table :data="CREDENTIALS" size="small" stripe>
        <el-table-column prop="roleName" label="角色" width="110" />
        <el-table-column prop="username" label="账号" width="120" class-name="cell-mono" />
        <el-table-column prop="password" label="口令" width="130" class-name="cell-mono" />
        <el-table-column prop="desc" label="职责说明" min-width="160" />
        <el-table-column label="操作" width="90" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="fillCredential(row)">填入</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="cred-tip">演示环境专用：审核人与提交人必须不同人，签发流程请用 admin 提交、approver01 审批。</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '@/api/system'
import { encryptPassword, resetPublicKeyCache } from '@/lib/password'
import { saveSessionOrg } from '@/lib/org'
import { savePerms, saveRoleName } from '@/lib/perm'
import logo from '@/assets/logo.svg'

const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

// 演示环境测试账号（与库中 ssl_sys_user 一一对应；新增演示账号后在此同步）
const CREDENTIALS = [
  { username: 'admin', password: '123456', roleName: '系统管理员', desc: '全局账号：申请录入、签发、机构与根CA维护' },
  { username: 'approver01', password: 'Ap2026qaz', roleName: '审核员', desc: '审核证书申请（不得与提交人同一人）' },
]

const credVisible = ref(false)

function fillCredential(row) {
  form.username = row.username
  form.password = row.password
  credVisible.value = false
  ElMessage.success(`已填入 ${row.roleName}（${row.username}），点「登录」进入`)
}

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }]
}

async function onSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    // 口令 SM2 加密后再离开发送（与 cslk HIS / csmig 同方案）
    const cipher = await encryptPassword(form.password)
    const res = await login({ username: form.username.trim(), passwordCipher: cipher })
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('username', res.data.username)
    // 不再缺省回退 ADMIN：后端未返回角色即视为异常，由守卫按未登录处理
    localStorage.setItem('role', res.data.role || '')
    // 角色名称与生效权限点：侧栏/守卫/按钮均按权限点收敛（服务端仍逐接口强校验）
    saveRoleName(res.data.roleName || '')
    savePerms(res.data.perms || [])
    // 机构域：orgKey=null 全局用户（落平台主机构），非空机构用户（固定本域）
    saveSessionOrg(res.data.orgKey || '', res.data.primaryOrg || '')
    ElMessage.success('登录成功')
    // 落到根路由（首页=概览；redirect 优先）
    const redirect = route.query.redirect || '/'
    router.push(redirect)
  } catch (e) {
    // 错误提示已由拦截器统一处理
    resetPublicKeyCache()
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #063059 0%, var(--his-primary-deep) 55%, #1b6fb8 100%);
}
.login-card {
  width: 400px;
  background: #fff;
  border-radius: 10px;
  padding: 36px 40px 28px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.25);
}
.login-title {
  text-align: center;
  margin-bottom: 28px;
}
.login-logo {
  width: 72px;
  height: 72px;
  margin: 0 auto 14px;
}
.login-logo img {
  display: block;
  width: 100%;
  height: 100%;
}
.login-title h1 {
  font-size: 20px;
  color: var(--his-title);
}
.login-title p {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
}
.login-btn {
  width: 100%;
  margin-top: 6px;
}
.login-tip {
  margin-top: 16px;
  text-align: center;
  font-size: 12px;
  color: #909399;
}
</style>
