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
        <el-tag effect="dark" size="small" style="margin-left: 8px" type="primary">
          当前操作域：{{ companyLabel }}
        </el-tag>
      </div>
      <div>
        <el-button
            v-perm="'ca:init'"
            :disabled="!canInitHere"
            :loading="initLoading"
            :title="canInitHere ? '' : '该公司已有独立根CA，仅可在尚无根时初始化'"
            type="primary"
            @click="onInit"
        >初始化 / 重建根CA
        </el-button>
        <el-button v-perm="'ca:root:download'" :disabled="!root.initialized" @click="onDownloadRoot">导出根证书
        </el-button>
        <el-button
            v-if="root.rootKeyState === 'ONLINE'"
            v-perm="'ca:seal'"
            type="warning"
            @click="onSeal"
        >封存根密钥（离线化）
        </el-button>
        <el-button
            v-if="root.rootKeyState === 'SEALED'"
            v-perm="'ca:unseal'"
            @click="onUnseal"
        >解封根密钥
        </el-button>
      </div>
    </div>

    <el-form inline style="margin-bottom: 12px">
      <el-form-item label="公司">
        <el-select v-model="company" placeholder="选择公司" style="width: 320px" @change="onCompanyChange">
          <el-option
              v-for="o in companyOptions"
              :key="o.uscc"
              :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
              :value="o.uscc"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <el-alert
        v-if="!root.initialized && root.message"
        :closable="false"
        :title="root.message"
        style="margin-bottom: 14px"
        type="warning"
    />

    <el-descriptions v-if="root.initialized" :column="3" border>
      <el-descriptions-item label="证书CN">{{ root.cn }}</el-descriptions-item>
      <el-descriptions-item label="算法">
        <el-tag size="small">{{ root.algorithm }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="序列号">{{ root.serialHex }}</el-descriptions-item>
      <el-descriptions-item label="生效时间">{{ root.validFrom }}</el-descriptions-item>
      <el-descriptions-item label="过期时间">{{ root.validTo }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ root.status }}</el-descriptions-item>
    </el-descriptions>

    <div v-if="root.subCa" class="subca-block">
      <div class="subca-title">
        服务签发二级CA（SUB_CA）
        <el-tag size="small" style="margin-left: 8px" type="info">{{ root.subCa.status }}</el-tag>
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
      <el-button v-perm="'ca:subca:download'" size="small" style="margin-top: 8px" @click="onDownloadSubCa">
        导出SUB_CA证书（中间CA链）
      </el-button>
    </div>

    <!-- ==================== Shamir(3,5) 门限分持 ==================== -->
    <div class="shamir-block">
      <div class="subca-title">
        根密钥 Shamir(3,5) 门限分持
        <el-tag :type="shares.generated ? 'success' : 'info'" size="small" style="margin-left: 8px">
          {{ shares.generated ? '已门限化（5 份，任 3 份可恢复）' : '未门限化（当前为单点加密落盘）' }}
        </el-tag>
      </div>
      <div class="shares-status">
        <template v-for="s in shareList" :key="s.index">
          <el-tag
              :type="s.exists ? 'success' : 'danger'"
              size="small"
              style="margin-right: 6px"
          >份额{{ s.index }}：{{ s.exists ? '已生成' : '缺失' }}
          </el-tag>
        </template>
      </div>
      <div style="margin-top: 10px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap">
        <el-button
            v-if="root.rootKeyState === 'ONLINE'"
            v-perm="'ca:share:migrate'"
            :loading="migrating"
            type="warning"
            @click="onMigrateShares"
        >迁移为门限分持（删除单点加密文件）
        </el-button>
        <template v-if="shares.generated">
          <el-button
              v-for="s in shareList"
              :key="s.index"
              v-perm="'ca:share:download'"
              :disabled="!s.exists"
              size="small"
              @click="onDownloadShare(s.index)"
          >下载份额{{ s.index }}
          </el-button>
          <el-button v-perm="'ca:seal'" plain type="danger" @click="onSeal">清除解封缓存（封存）</el-button>
        </template>
      </div>
      <div v-if="shares.generated" class="unseal-form">
        <div class="unseal-title">份额解封（提交任意 3 份，重组根私钥仅存内存）</div>
        <el-input
            v-for="i in 3"
            :key="i"
            v-model="shareInputs[i - 1]"
            :placeholder="`份额 JSON（从“下载份额${i}”获取）`"
            :rows="2"
            style="margin-bottom: 8px"
            type="textarea"
        />
        <el-button v-perm="'ca:share:unseal'" :disabled="shareInputs.filter(s => s && s.trim()).length < 3" :loading="unsealing"
                   type="primary"
                   @click="onUnsealShares">
          提交份额解封
        </el-button>
      </div>
    </div>

    <!-- ==================== 我的密钥份额（仅保管人可见） ==================== -->
    <div class="rebuild-block">
      <div class="subca-title">
        我的密钥份额（根私钥门限分持）
        <el-tag v-if="myShareData" size="small" style="margin-left: 8px" type="primary">
          份额 {{ myShareData.index }}/{{ myShareData.total }} · 阈值 {{ myShareData.threshold }}
        </el-tag>
      </div>
      <template v-if="myShareData">
        <el-alert :closable="false" class="dialog-tip" show-icon title="这是你本人持有的根私钥门限份额，请离线保管；审批二级CA重建申请时须提交这份份额。"
                  type="warning"/>
        <el-input :model-value="myShareData.shareJson" :rows="5" class="share-view" readonly type="textarea"/>
        <div style="margin-top: 8px; display: flex; gap: 8px">
          <el-button size="small" type="primary" @click="onCopyMyShare">复制我的份额</el-button>
          <el-button size="small" @click="onDownloadMyShare">下载我的份额(JSON)</el-button>
          <el-button size="small" @click="goApprovalCenter">审批中心</el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  downloadCaShare,
  downloadRootCert,
  downloadSubCaCert,
  getCaShares,
  getCaStatus,
  getShareOverview,
  initCa,
  listCompaniesCached,
  migrateCaShares,
  sealRootKey,
  unsealCaShares,
  unsealRootKey
} from '@/api/system'
import {downloadText} from '@/lib/download'
import {copyText} from '@/lib/clipboard'

const router = useRouter()

// 操作域由页面内显式选择（根CA按公司独立）
const company = ref('')
const companyOptions = ref([])
const companyLabel = computed(() => {
  const hit = companyOptions.value.find(t => t.uscc === company.value)
  return hit?.companyName || company.value
})

const root = ref({})
const initLoading = ref(false)
const migrating = ref(false)
const unsealing = ref(false)
const shares = ref({generated: false, total: 5, threshold: 3, shares: []})
const shareInputs = ref(['', '', ''])

const shareList = computed(() => shares.value.shares || [])

// 仅在公司尚无独立根时允许初始化（公司创建即自动生成根，防误重建）
const canInitHere = computed(() => !root.value.initialized)

const rootKeyTagType = computed(() => {
  const state = root.value.rootKeyState
  if (state === 'SEALED') return 'warning'
  if (state === 'SHARES') return 'primary'
  if (state === 'MISSING') return 'danger'
  return 'success'
})
const rootKeyLabel = computed(() => {
  const state = root.value.rootKeyState
  if (state === 'SEALED') return '根密钥已封存（离线）'
  if (state === 'SHARES') return '根密钥已门限分持（3/5）'
  if (state === 'MISSING') return '根密钥缺失'
  return '根密钥在线'
})

async function load() {
  const res = await getCaStatus(company.value)
  root.value = res.data
  await loadShares()
  await loadMyShare()
}

async function loadShares() {
  try {
    const res = await getCaShares(company.value)
    shares.value = res.data || {generated: false, total: 5, threshold: 3, shares: []}
  } catch (e) {
    shares.value = {generated: false, total: 5, threshold: 3, shares: []}
  }
}

async function onInit() {
  const confirmed = await ElMessageBox.confirm(
      `将在公司域「${companyLabel.value}」上初始化独立根CA。若旧根私钥丢失，重建会导致已签发证书与 SUB_CA 全部失效。新根生成后会自动切换为 Shamir(3,5) 门限分持。确定继续吗？`,
      `初始化根CA（${companyLabel.value}）`,
      {type: 'warning', confirmButtonText: '确定初始化', cancelButtonText: '取消'}
  ).catch(() => false)
  if (!confirmed) return
  initLoading.value = true
  try {
    const res = await initCa(company.value)
    root.value = res.data
    ElMessage.success(res.data.message || '根CA初始化完成')
    await loadShares()
  } finally {
    initLoading.value = false
  }
}

async function onSeal() {
  const confirmed = await ElMessageBox.confirm(
      root.value.rootKeyState === 'SHARES'
          ? '将清除本次门限解封的内存根私钥缓存（门限份额不受影响，仍为唯一持久形态）。确定封存吗？'
          : '封存后根CA私钥进入离线状态（key.pem.enc 移存为 .sealed），日常签发由 SUB_CA 承担；轮换 SUB_CA 等维护操作需先解封。确定封存吗？',
      '封存根密钥',
      {type: 'warning', confirmButtonText: '确定封存', cancelButtonText: '取消'}
  ).catch(() => false)
  if (!confirmed) return
  const res = await sealRootKey(company.value)
  root.value = res.data
  ElMessage.success(res.message || '根密钥已封存')
  await loadShares()
}

async function onUnseal() {
  const confirmed = await ElMessageBox.confirm(
      '解封将使根CA私钥恢复在线态（仅建议在轮换 SUB_CA 等维护窗口内使用）。确定解封吗？',
      '解封根密钥',
      {type: 'warning', confirmButtonText: '确定解封', cancelButtonText: '取消'}
  ).catch(() => false)
  if (!confirmed) return
  const res = await unsealRootKey(company.value)
  root.value = res.data
  ElMessage.success(res.message || '根密钥已解封')
  await loadShares()
}

async function onMigrateShares() {
  const confirmed = await ElMessageBox.confirm(
      '迁移将把当前 SM4 加密落盘的根私钥拆为 5 份 Shamir(3,5) 门限份额并删除原始加密文件。请先准备好 5 个离线保管位置（U盘/保险柜/不同责任人）。确定迁移吗？',
      '迁移为门限分持',
      {type: 'warning', confirmButtonText: '确定迁移', cancelButtonText: '取消'}
  ).catch(() => false)
  if (!confirmed) return
  migrating.value = true
  try {
    const res = await migrateCaShares(company.value)
    shares.value = res.data
    ElMessage.success(res.message || '门限分持完成，请下载 5 份份额离线保管')
  } finally {
    migrating.value = false
  }
}

async function onDownloadShare(index) {
  const res = await downloadCaShare(index, company.value)
  const share = res.data
  downloadText(JSON.stringify(share, null, 2), `csssl-root-share-${company.value}-${index}.json`)
  ElMessage.success(`份额 ${index} 已导出，请离线保管（勿与本机同存）`)
}

async function onUnsealShares() {
  unsealing.value = true
  try {
    const res = await unsealCaShares(shareInputs.value.map(s => s.trim()).filter(Boolean), company.value)
    root.value = res.data
    ElMessage.success(res.message || '根私钥已通过份额解封（内存缓存）')
  } finally {
    unsealing.value = false
  }
}

async function onDownloadRoot() {
  const pem = await downloadRootCert(company.value)
  downloadText(pem, `csssl-root-ca-${company.value}.pem`)
  ElMessage.success('根证书已导出')
}

async function onDownloadSubCa() {
  const pem = await downloadSubCaCert(company.value)
  downloadText(pem, `csssl-sub-ca-${company.value}.pem`)
  ElMessage.success('SUB_CA证书已导出')
}

async function onCompanyChange() {
  shareInputs.value = ['', '', '']
  root.value = {}
  await load()
}

// ==================== 我的密钥份额（仅保管人可见） ====================
const myShareData = ref(null)

async function loadMyShare() {
  if (!company.value) return
  try {
    const res = await getShareOverview(company.value)
    myShareData.value = (res.data || {}).mine || null
  } catch (e) {
    myShareData.value = null
  }
}

async function onCopyMyShare() {
  if (!myShareData.value) return
  const ok = await copyText(myShareData.value.shareJson)
  ok ? ElMessage.success('已复制') : ElMessage.error('复制失败，请手动选择复制')
}

function onDownloadMyShare() {
  if (!myShareData.value) return
  downloadText(myShareData.value.shareJson, `csssl-root-share-${company.value}-${myShareData.value.index}.json`)
  ElMessage.success('我的份额已导出，请离线保管')
}

function goApprovalCenter() {
  router.push('/approval-center')
}

onMounted(async () => {
  companyOptions.value = await listCompaniesCached()
  const active = companyOptions.value.find(t => t.status === 'ACTIVE') || companyOptions.value[0]
  company.value = active?.uscc || ''
  if (company.value) await load()
})
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title);
  display: flex;
  align-items: center;
}

.subca-block,
.shamir-block,
.rebuild-block {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
  background: #fafbfe;
}

.share-view :deep(.el-textarea__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: #1e293b;
  background: #f7f9fc;
}

.subca-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--his-title);
  margin-bottom: 10px;
  display: flex;
  align-items: center;
}

.shares-status {
  margin-top: 8px;
}

.unseal-form {
  margin-top: 12px;
  padding: 12px;
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  background: #fff;
}

.unseal-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--his-title);
  margin-bottom: 8px;
}
</style>
