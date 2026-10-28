<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="section-title">
          机构管理（多机构·按机构独立根CA）
          <el-tag size="small" type="info" style="margin-left: 8px">仅全局 ADMIN</el-tag>
        </div>
        <div>
          <el-button @click="load">刷新</el-button>
          <el-button type="primary" @click="openCreate">新建机构</el-button>
        </div>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="新建机构将同步生成该机构独立根CA与SUB_CA（耗时数秒）；各机构证书链、CRL、OCSP 完全隔离，跨机构验证互不可信。"
        style="margin-bottom: 14px"
      />

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="orgKey" label="机构标识" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.orgKey }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="orgName" label="机构名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="subjectO" label="主体O" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.subjectO || '-' }}</template>
        </el-table-column>
        <el-table-column prop="algorithm" label="算法" width="90">
          <template #default="{ row }">{{ row.algorithm || '-' }}</template>
        </el-table-column>
        <el-table-column label="机构状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="根密钥状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.rootKeyState" size="small" :type="rootKeyTag(row.rootKeyState)">{{ rootKeyText(row.rootKeyState) }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="根CA有效期" min-width="170">
          <template #default="{ row }">{{ validity(row.rootValidFrom, row.rootValidTo) }}</template>
        </el-table-column>
        <el-table-column label="SUB_CA有效期" min-width="170">
          <template #default="{ row }">{{ validity(row.subCaValidFrom, row.subCaValidTo) }}</template>
        </el-table-column>
        <el-table-column prop="contact" label="联系人" width="110">
          <template #default="{ row }">{{ row.contact || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ACTIVE'"
              link
              type="warning"
              size="small"
              @click="onSuspend(row)"
            >暂停</el-button>
            <el-button
              v-if="row.status === 'SUSPENDED'"
              link
              type="success"
              size="small"
              @click="onResume(row)"
            >恢复</el-button>
            <el-button
              v-if="row.status !== 'CLOSED'"
              link
              type="danger"
              size="small"
              @click="onClose(row)"
            >关闭</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="暂无机构（default 默认域不在此列）" />
    </div>

    <!-- 新建机构 -->
    <el-dialog v-model="createVisible" title="新建机构（创建即生成独立根CA）" width="540px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="机构标识" prop="orgKey">
          <el-input v-model="form.orgKey" placeholder="^[a-z0-9]{1,32}$，如 yibao / huxi（创建后不可改）" />
        </el-form-item>
        <el-form-item label="机构名称" prop="orgName">
          <el-input v-model="form.orgName" placeholder="如 医保结算中心" />
        </el-form-item>
        <el-form-item label="主体O" prop="subjectO">
          <el-input v-model="form.subjectO" placeholder="证书 Subject O（该机构全部证书的机构主体口径）" />
        </el-form-item>
        <el-form-item label="算法" prop="algorithm">
          <el-radio-group v-model="form.algorithm">
            <el-radio-button value="SM2">SM2 国密</el-radio-button>
            <el-radio-button value="RSA2048">RSA 2048</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact" placeholder="机构CA管理员（可选）" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="用途/边界说明（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="creating" @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="onCreate">
            {{ creating ? '正在生成独立根CA…' : '创建机构' }}
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listOrgs, createOrg, suspendOrg, resumeOrg, closeOrg, listOrgsCached } from '@/api/system'

const loading = ref(false)
const rows = ref([])

const createVisible = ref(false)
const creating = ref(false)
const formRef = ref()
const form = reactive({ orgKey: '', orgName: '', subjectO: '', algorithm: 'SM2', contact: '', description: '' })
const rules = {
  orgKey: [
    { required: true, message: '请输入机构标识', trigger: 'blur' },
    { pattern: /^[a-z0-9]{1,32}$/, message: '仅小写字母与数字，长度1-32', trigger: 'blur' }
  ],
  orgName: [{ required: true, message: '请输入机构名称', trigger: 'blur' }],
  subjectO: [{ required: true, message: '请输入主体O（机构证书主体口径）', trigger: 'blur' }]
}

function statusText(s) {
  return { ACTIVE: '启用', SUSPENDED: '已暂停', CLOSED: '已关闭' }[s] || s || '-'
}
function statusTag(s) {
  return { ACTIVE: 'success', SUSPENDED: 'warning', CLOSED: 'info' }[s] || 'info'
}
function rootKeyText(s) {
  return { ONLINE: 'ONLINE 在线', SEALED: 'SEALED 封存', THRESHOLD: 'THRESHOLD 门限分持' }[s] || s
}
function rootKeyTag(s) {
  return { ONLINE: 'success', SEALED: 'warning', THRESHOLD: 'primary' }[s] || 'info'
}
function validity(from, to) {
  if (!from && !to) return '-'
  return `${from || '?'} ~ ${to || '?'}`
}

async function load() {
  loading.value = true
  try {
    const res = await listOrgs()
    const d = res.data
    rows.value = Array.isArray(d) ? d : d?.records || []
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { orgKey: '', orgName: '', subjectO: '', algorithm: 'SM2', contact: '', description: '' })
  createVisible.value = true
}

async function onCreate() {
  await formRef.value.validate()
  creating.value = true
  ElMessage.info('正在生成独立根CA，请稍候（可能需要数秒）…')
  try {
    await createOrg({ ...form })
    ElMessage.success('机构已创建，独立根CA与SUB_CA生成完成')
    createVisible.value = false
    await Promise.all([load(), listOrgsCached(true)])
  } finally {
    creating.value = false
  }
}

async function onSuspend(row) {
  await ElMessageBox.confirm(
    `确定暂停机构 ${row.orgKey} 吗？暂停后该机构停止签发与新验证请求，已签发证书与CRL仍可查询。`,
    '暂停机构',
    { type: 'warning', confirmButtonText: '确定暂停', cancelButtonText: '取消' }
  )
  await suspendOrg(row.orgKey)
  ElMessage.success('机构已暂停')
  await Promise.all([load(), listOrgsCached(true)])
}

async function onResume(row) {
  await ElMessageBox.confirm(`确定恢复机构 ${row.orgKey} 吗？恢复后该机构业务操作重新开放。`, '恢复机构', { type: 'warning' })
  await resumeOrg(row.orgKey)
  ElMessage.success('机构已恢复')
  await Promise.all([load(), listOrgsCached(true)])
}

async function onClose(row) {
  // 关闭不可逆：二次输入 orgKey 确认
  const { value } = await ElMessageBox.prompt(
    `关闭机构 ${row.orgKey} 后其根CA停用、不可再签发（不可逆）。请输入机构标识以确认：`,
    '关闭机构',
    {
      type: 'error',
      confirmButtonText: '确定关闭',
      cancelButtonText: '取消',
      inputPlaceholder: `输入 ${row.orgKey}`,
      inputValidator: v => (v === row.orgKey ? true : '机构标识输入不一致')
    }
  )
  if (value !== row.orgKey) return
  await closeOrg(row.orgKey)
  ElMessage.success('机构已关闭')
  await Promise.all([load(), listOrgsCached(true)])
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d2a4a;
  display: flex;
  align-items: center;
}
.mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}
</style>
