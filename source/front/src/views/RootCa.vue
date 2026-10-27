<template>
  <div class="page-card">
    <div class="table-toolbar">
      <div class="section-title">
        根CA管理
        <el-tag :type="root.initialized ? 'success' : 'danger'" size="small" style="margin-left: 8px">
          {{ root.initialized ? '已初始化' : '未就绪' }}
        </el-tag>
        <el-tag
          v-if="root.rootKeyState"
          :type="rootKeyTagType"
          size="small"
          style="margin-left: 8px"
        >
          {{ rootKeyLabel }}
        </el-tag>
      </div>
      <div>
        <el-button type="primary" :loading="initLoading" @click="onInit">初始化 / 重建根CA</el-button>
        <el-button :disabled="!root.initialized" @click="onDownloadRoot">导出根证书</el-button>
        <el-button
          v-if="root.rootKeyState === 'ONLINE'"
          type="warning"
          @click="onSeal"
        >封存根密钥（离线化）</el-button>
        <el-button
          v-if="root.rootKeyState === 'SEALED'"
          @click="onUnseal"
        >解封根密钥</el-button>
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

    <div class="subca-block" v-if="root.subCa">
      <div class="subca-title">
        服务签发二级CA（SUB_CA）
        <el-tag size="small" type="info" style="margin-left: 8px">{{ root.subCa.status }}</el-tag>
      </div>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="证书CN">{{ root.subCa.cn }}</el-descriptions-item>
        <el-descriptions-item label="算法">
          <el-tag size="small">{{ root.subCa.algorithm }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="序列号">{{ root.subCa.serialHex }}</el-descriptions-item>
        <el-descriptions-item label="生效时间">{{ root.subCa.validFrom }}</el-descriptions-item>
        <el-descriptions-item label="过期时间">{{ root.subCa.validTo }}</el-descriptions-item>
        <el-descriptions-item label="到期前自动轮换">
          剩余 &lt; {{ root.subCa.renewBeforeDays }} 天
        </el-descriptions-item>
      </el-descriptions>
      <el-button size="small" style="margin-top: 8px" @click="onDownloadSubCa">
        导出SUB_CA证书（中间CA链）
      </el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      style="margin-top: 16px"
      title="说明"
      description="对齐国内持证CA通行架构：根CA仅初始化与签发二级CA（SUB_CA），日常证书签发、CRL与OCSP全部由 SUB_CA 承担。根私钥经 SM4 加密落盘（key.pem.enc），可「封存」离线——封存后根私钥不再参与在线操作，直到解封。若根私钥丢失会吊销旧根并重建新根（旧证书全部失效，请谨慎操作）。"
    />
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getCaStatus,
  initCa,
  downloadRootCert,
  downloadSubCaCert,
  sealRootKey,
  unsealRootKey
} from '@/api/system'
import { downloadText } from '@/lib/download'

const root = ref({})
const initLoading = ref(false)

const rootKeyTagType = computed(() => {
  const state = root.value.rootKeyState
  if (state === 'SEALED') return 'warning'
  if (state === 'MISSING') return 'danger'
  return 'success'
})
const rootKeyLabel = computed(() => {
  const state = root.value.rootKeyState
  if (state === 'SEALED') return '根密钥已封存（离线）'
  if (state === 'MISSING') return '根密钥缺失'
  return '根密钥在线'
})

async function load() {
  const res = await getCaStatus()
  root.value = res.data
}

async function onInit() {
  const confirmed = await ElMessageBox.confirm(
    '初始化将生成（或重建）根CA。若旧根私钥丢失，重建会导致已签发证书与 SUB_CA 全部失效。确定继续吗？',
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

async function onSeal() {
  const confirmed = await ElMessageBox.confirm(
    '封存后根CA私钥进入离线状态（key.pem.enc 移存为 .sealed），日常签发由 SUB_CA 承担；轮换 SUB_CA 等维护操作需先解封。确定封存吗？',
    '封存根密钥',
    { type: 'warning', confirmButtonText: '确定封存', cancelButtonText: '取消' }
  ).catch(() => false)
  if (!confirmed) return
  const res = await sealRootKey()
  root.value = res.data
  ElMessage.success(res.message || '根密钥已封存')
}

async function onUnseal() {
  const confirmed = await ElMessageBox.confirm(
    '解封将使根CA私钥恢复在线态（仅建议在轮换 SUB_CA 等维护窗口内使用）。确定解封吗？',
    '解封根密钥',
    { type: 'warning', confirmButtonText: '确定解封', cancelButtonText: '取消' }
  ).catch(() => false)
  if (!confirmed) return
  const res = await unsealRootKey()
  root.value = res.data
  ElMessage.success(res.message || '根密钥已解封')
}

async function onDownloadRoot() {
  const pem = await downloadRootCert()
  downloadText(pem, 'csssl-root-ca.pem')
  ElMessage.success('根证书已导出')
}

async function onDownloadSubCa() {
  const pem = await downloadSubCaCert()
  downloadText(pem, 'csssl-sub-ca.pem')
  ElMessage.success('SUB_CA证书已导出')
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
.subca-block {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
  background: #fafbfe;
}
.subca-title {
  font-size: 14px;
  font-weight: 600;
  color: #1d2a4a;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
}
</style>
