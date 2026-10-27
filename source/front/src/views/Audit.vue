<template>
  <div class="page-card">
    <div class="table-toolbar">
      <div class="section-title">审计日志</div>
      <el-select v-model="action" placeholder="操作类型" clearable style="width: 160px" @change="reload">
        <el-option v-for="item in actions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="操作人" width="130" />
      <el-table-column label="操作" width="130">
        <template #default="{ row }">{{ actionText(row.action) }}</template>
      </el-table-column>
      <el-table-column prop="target" label="对象" width="120" />
      <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
      <el-table-column prop="ip" label="来源IP" width="140" />
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column label="防篡改" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="onVerify(row)">验签</el-button>
          <el-tag v-if="row.sign" size="small" type="success">已签名</el-tag>
          <el-tag v-else size="small" type="info">无签名</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNo"
      v-model:page-size="query.pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 14px; justify-content: flex-end"
      @change="load"
    />

    <el-dialog v-model="verifyVisible" title="审计验签（GM/T 0034 §10.1/§10.5 防篡改校验）" width="640px">
      <el-result v-if="verify?.valid" icon="success" title="签名有效" sub-title="记录未被篡改，由审计签名密钥签发，哈希链指向的记录签名一致" />
      <el-result v-else-if="verify" icon="error" title="签名校验失败" sub-title="记录内容已被改动或签名缺失" />
      <div v-else style="padding: 20px; text-align: center; color: #909399;">验签中…</div>
      <el-descriptions v-if="verify" :column="1" border>
        <el-descriptions-item label="记录ID">{{ verify.id }}</el-descriptions-item>
        <el-descriptions-item label="规范化串（canonical）">
          <div class="mono-wrap">{{ verify.canonical }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="签名值（SM3withSM2 hex）">
          <div class="mono-wrap">{{ verify.sign }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="哈希链上一签名">
          <div class="mono-wrap">{{ verify.prevSign || '（无，首条/历史记录）' }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { pageAudit, verifyAuditLog } from '@/api/system'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const action = ref('')
const query = reactive({ pageNo: 1, pageSize: 10 })
const verifyVisible = ref(false)
const verify = ref(null)

const actions = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '退出' },
  { value: 'CA_INIT', label: '初始化根CA' },
  { value: 'CA_SEAL', label: '封存根私钥' },
  { value: 'CA_UNSEAL', label: '解封根私钥' },
  { value: 'CERT_ISSUE', label: '签发证书' },
  { value: 'CERT_CSR_ISSUE', label: 'CSR签发' },
  { value: 'CERT_PUBKEY_ISSUE', label: '公钥签发' },
  { value: 'CERT_REVOKE', label: '吊销证书' },
  { value: 'CERT_SUSPEND', label: '挂起证书' },
  { value: 'CERT_UNSUSPEND', label: '解除挂起' },
  { value: 'CERT_RENEW', label: '证书更新' },
  { value: 'CERT_REKEY', label: '密钥更新' },
  { value: 'CERT_DOWNLOAD', label: '下载证书/私钥' },
  { value: 'CRL_DOWNLOAD', label: '下载CRL' },
  { value: 'RA_SUBMIT', label: 'RA提交申请' },
  { value: 'RA_APPROVE', label: 'RA批准' },
  { value: 'RA_REJECT', label: 'RA驳回' },
  { value: 'RA_ISSUE', label: 'RA签发' },
  { value: 'RA_CANCEL', label: 'RA撤销' },
  { value: 'TSA_STAMP', label: '时间戳盖章' }
]

function actionText(a) {
  return actions.find(i => i.value === a)?.label || a
}

async function onVerify(row) {
  verify.value = null
  verifyVisible.value = true
  try {
    verify.value = (await verifyAuditLog(row.id)).data
  } catch (e) {
    verifyVisible.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const res = await pageAudit({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      action: action.value || undefined
    })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  query.pageNo = 1
  load()
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d2a4a;
}
.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
}
</style>
