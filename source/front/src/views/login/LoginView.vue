<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-title">
        <div class="login-logo">
          <img :src="logo" alt="长沙麓康CA认证中心"/>
        </div>
        <h1>长沙麓康CA认证中心</h1>
        <p>证书签发 · 吊销 CRL · 国密加密传输</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="账号" :prefix-icon="User"/>
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
        <el-link type="primary" underline="never" class="cred-link" @click="credVisible = true">登录凭证</el-link>
      </div>
    </div>

    <!-- 登录凭证（演示环境测试账号一览：口令统一 123456，可复制账号或一键填入） -->
    <el-dialog v-model="credVisible" title="登录凭证（密码：123456）" width="850px" top="10vh">
      <div class="cred-body">
        <el-input
            v-model="credKeyword"
            class="cred-filter"
            clearable
            placeholder="按角色 / 账号 / 姓名筛选"
            :prefix-icon="Search"
        />
        <el-table :data="sortedCredRows" border size="small" :span-method="credSpanMethod">
          <el-table-column label="角色" min-width="110" prop="role"/>
          <el-table-column label="账号（姓名）" min-width="180">
            <template #default="{row}">
              <span class="cred-login">{{ row.login }}</span>
              <el-button link type="primary" size="small" @click="handleCopyAccount(row.login)">复制账号</el-button>
              <span class="cred-name">（{{ row.name }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="能做什么" min-width="300" prop="ability"/>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {Lock, Search, User} from '@element-plus/icons-vue'
import {login} from '@/api/system'
import {encryptPassword, resetPublicKeyCache} from '@/lib/password'
import {saveSessionOrg} from '@/lib/org'
import {savePerms, saveRoleName} from '@/lib/perm'
import {copyText} from '@/lib/clipboard'
import logo from '@/assets/logo.svg'

const router = useRouter()
const route = useRoute()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

// ===== 演示账号凭据（与后端 ssl_sys_user 同步；口令已统一为 123456）=====
// 数据来源：直读 ssl_ca.ssl_sys_user（2026-10-29）—— admin / suwenwen 启用，wanghao / songqingran 停用
const credVisible = ref(false)

const credRows = [
  {
    role: '系统管理员',
    login: 'admin',
    name: '系统管理员',
    ability: '用户/角色管理、全量兜底（含全部操作权限）',
  },
  {
    role: '审计员',
    login: 'wanghao',
    name: '王浩',
    ability: '仅可查看审计与证书，不可签发/审核/管理',
  },
  {
    role: 'CA操作员',
    login: 'songqingran',
    name: '宋清然',
    ability: '录入证书申请、执行签发（签发与审核分离）',
  },
  {
    role: '审核员',
    login: 'suwenwen',
    name: '苏雯雯',
    ability: '审核证书申请（不得与录入同一人）',
  }
]

// 凭据关键词筛选（纯前端）：角色 / 登录名 / 姓名 模糊匹配
const credKeyword = ref('')
const filteredCredRows = computed(() => {
  const kw = credKeyword.value.trim().toLowerCase()
  if (!kw) {
    return credRows
  }
  return credRows.filter((row) =>
      [row.role, row.login, row.name].join(' ').toLowerCase().includes(kw))
})

// 按角色中文序排序，再用 span-method 合并同角色单元格（多账号同角色时生效）
const sortedCredRows = computed(() => {
  const list = filteredCredRows.value.slice()
  list.sort((a, b) => a.role.localeCompare(b.role, 'zh-Hans-CN'))
  return list
})

// 预算每行「角色」列的跨行数：同角色首行 rowspan=组内行数，后续行 rowspan=0（被合并）
const credSpans = computed(() => {
  const list = sortedCredRows.value
  const span = []
  for (let i = 0; i < list.length; i++) {
    if (i > 0 && list[i].role === list[i - 1].role) {
      span.push(0)
    } else {
      let n = 1
      while (i + n < list.length && list[i + n].role === list[i].role) n++
      span.push(n)
    }
  }
  return span
})

const credSpanMethod = ({columnIndex, rowIndex}) => {
  if (columnIndex !== 0) {
    return {rowspan: 1, colspan: 1}
  }
  const v = credSpans.value[rowIndex]
  return {rowspan: v === undefined ? 1 : v, colspan: 1}
}

// 复制登录名（账号本身，无需解析）
const handleCopyAccount = async (login) => {
  const ok = await copyText(login)
  if (ok) {
    ElMessage.success(`已复制账号：${login}`)
  } else {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

const rules = {
  username: [{required: true, message: '请输入账号', trigger: 'blur'}],
  password: [{required: true, message: '请输入口令', trigger: 'blur'}]
}

async function onSubmit() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    // 校验未通过：Element Plus 已在对应字段下方内联提示，无需额外报错，直接返回
    return
  }
  loading.value = true
  try {
    // 口令 SM2 加密后再离开发送（与 cslk HIS / csmig 同方案）
    const cipher = await encryptPassword(form.password)
    const res = await login({username: form.username.trim(), passwordCipher: cipher})
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
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #909399;
}

.cred-link {
  font-size: 12px;
}

.cred-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.cred-body {
  padding-right: 4px;
}

.cred-filter {
  margin-bottom: 10px;
}

.cred-login {
  font-family: Consolas, Monaco, monospace;
  font-weight: 600;
  color: #1f2d3d;
}

.cred-name {
  margin-left: 2px;
  color: #909399;
}

.cred-disabled {
  margin-left: 6px;
}

.cred-pwd {
  font-family: Consolas, Monaco, monospace;
  font-weight: 600;
  color: #c0392b;
}
</style>
