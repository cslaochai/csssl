<template>
  <div class="page-card">
    <div class="table-toolbar">
      <div class="section-title">
        根CA管理
        <el-tag :type="root.initialized ? 'success' : 'danger'" size="small" style="margin-left: 8px">
          {{ root.initialized ? '已初始化' : '未就绪' }}
        </el-tag>
      </div>
      <div>
        <el-button type="primary" :loading="initLoading" @click="onInit">初始化 / 重建根CA</el-button>
        <el-button :disabled="!root.initialized" @click="onDownloadRoot">导出根证书</el-button>
      </div>
    </div>

    <el-alert
      v-if="!root.initialized && root.message"
      :title="root.message"
      type="warning"
      :closable="false"
      style="margin-bottom: 14px"
    />

    <el-descriptions :column="3" border v-if="root.initialized">
      <el-descriptions-item label="证书CN">{{ root.cn }}</el-descriptions-item>
      <el-descriptions-item label="算法">
        <el-tag size="small">{{ root.algorithm }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="序列号">{{ root.serialHex }}</el-descriptions-item>
      <el-descriptions-item label="生效时间">{{ root.validFrom }}</el-descriptions-item>
      <el-descriptions-item label="过期时间">{{ root.validTo }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ root.status }}</el-descriptions-item>
    </el-descriptions>

    <el-alert
      type="info"
      :closable="false"
      style="margin-top: 16px"
      title="说明"
      description="根CA密钥只落盘（store-dir/root/key.pem），数据库仅存公钥证书。初始化接口幂等：已存在则原样返回；若私钥文件丢失会吊销旧根并重建新根（旧证书全部失效，请谨慎操作）。"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCaStatus, initCa, downloadRootCert } from '@/api/system'
import { downloadText } from '@/lib/download'

const root = ref({})
const initLoading = ref(false)

async function load() {
  const res = await getCaStatus()
  root.value = res.data
}

async function onInit() {
  const confirmed = await ElMessageBox.confirm(
    '初始化将生成（或重建）根CA。若旧根私钥丢失，重建会导致已签发证书全部失效。确定继续吗？',
    '初始化根CA',
    { type: 'warning', confirmButtonText: '确定初始化', cancelButtonText: '取消' }
  ).catch(() => false)
  if (!confirmed) return
  initLoading.value = true
  try {
    const res = await initCa()
    root.value = res.data
    ElMessage.success(res.data.message || '根CA初始化完成')
  } finally {
    initLoading.value = false
  }
}

async function onDownloadRoot() {
  const pem = await downloadRootCert()
  downloadText(pem, 'csssl-root-ca.pem')
  ElMessage.success('根证书已导出')
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
</style>
