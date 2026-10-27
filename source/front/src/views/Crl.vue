<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="section-title">吊销列表（CRL）</div>
        <el-button type="primary" :loading="crlLoading" @click="onDownloadCrl">下载 CRL</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="subjectCn" label="主体CN" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ row.certType }}</template>
        </el-table-column>
        <el-table-column prop="serialHex" label="序列号" min-width="160" show-overflow-tooltip />
        <el-table-column label="吊销原因" min-width="140">
          <template #default="{ row }">{{ reasonText(row.revokeReason) }}</template>
        </el-table-column>
        <el-table-column prop="revokedAt" label="吊销时间" width="170" />
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="暂无吊销记录" />
    </div>

    <!-- CRL 内容预览 -->
    <div class="page-card" v-if="crlPem">
      <div class="section-title">CRL 内容（PEM，由根CA签名）</div>
      <el-input
        :model-value="crlPem"
        type="textarea"
        :rows="10"
        readonly
        class="crl-preview"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getCrlView, downloadCrl } from '@/api/system'
import { downloadText } from '@/lib/download'

const loading = ref(false)
const crlLoading = ref(false)
const rows = ref([])
const crlPem = ref('')

function reasonText(r) {
  const map = {
    KEY_COMPROMISE: '密钥泄露',
    CA_COMPROMISE: 'CA泄露',
    AFFILIATION_CHANGED: '隶属关系变更',
    SUPERSEDED: '被新证书替代',
    CESSATION_OF_OPERATION: '停止运营',
    CERTIFICATE_HOLD: '证书挂起',
    PRIVILEGE_WITHDRAWN: '权限撤销',
    ROOT_KEY_LOST: '根CA密钥丢失重建',
    UNSPECIFIED: '未指定'
  }
  return map[r] || r || '未指定'
}

async function load() {
  loading.value = true
  try {
    const res = await getCrlView()
    rows.value = res.data.revoked || []
    crlPem.value = res.data.crlPem || ''
  } finally {
    loading.value = false
  }
}

async function onDownloadCrl() {
  crlLoading.value = true
  try {
    const pem = await downloadCrl()
    downloadText(pem, 'csssl-crl.pem')
    ElMessage.success('CRL已下载')
  } finally {
    crlLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d2a4a;
}
.crl-preview {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}
</style>
