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
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { pageAudit } from '@/api/system'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const action = ref('')
const query = reactive({ pageNo: 1, pageSize: 10 })

const actions = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '退出' },
  { value: 'CA_INIT', label: '初始化根CA' },
  { value: 'CERT_ISSUE', label: '签发证书' },
  { value: 'CERT_CSR_ISSUE', label: 'CSR签发' },
  { value: 'CERT_REVOKE', label: '吊销证书' },
  { value: 'CERT_DOWNLOAD', label: '下载证书/私钥' },
  { value: 'CRL_DOWNLOAD', label: '下载CRL' }
]

function actionText(a) {
  return actions.find(i => i.value === a)?.label || a
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
</style>
