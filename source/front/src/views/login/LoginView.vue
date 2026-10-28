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
      <div class="login-tip">如忘记口令，请联系系统管理员重置</div>
    </div>
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
import logo from '@/assets/logo.svg'

const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

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
    // 机构域：orgKey=null 全局用户（可选域），非空机构用户（固定本域）
    saveSessionOrg(res.data.orgKey || '')
    ElMessage.success('登录成功')
    const redirect = route.query.redirect || '/dashboard'
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
  background: linear-gradient(135deg, #1d2a4a 0%, #2d4a7a 55%, #3d6aa8 100%);
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
  color: #1d2a4a;
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
