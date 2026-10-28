<template>
  <div>
    <div class="page-card">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- 全量 CRL -->
        <el-tab-pane label="全量 CRL" name="full">
          <div class="table-toolbar">
            <div class="section-title">
              吊销列表（CRL）
              <el-tag size="small" :type="orgDomain === 'default' ? 'info' : 'warning'" effect="plain" style="margin-left: 8px">当前域：{{ orgDomain }}</el-tag>
            </div>
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

          <!-- CRL 元信息 + 内容预览 -->
          <template v-if="crlPem">
            <div class="section-title" style="margin-top: 16px">CRL 内容（PEM，由服务签发二级CA签名）</div>
            <el-descriptions :column="3" border size="small" style="margin: 12px 0">
              <el-descriptions-item label="签发者（CA）">{{ meta.issuerCn }}</el-descriptions-item>
              <el-descriptions-item label="本次更新（thisUpdate）">{{ meta.thisUpdate }}</el-descriptions-item>
              <el-descriptions-item label="下次更新（nextUpdate）">{{ meta.nextUpdate }}</el-descriptions-item>
              <el-descriptions-item label="吊销条目数">{{ meta.entryCount }}</el-descriptions-item>
            </el-descriptions>
            <el-input
              :model-value="crlPem"
              type="textarea"
              :rows="10"
              readonly
              class="crl-preview"
            />
          </template>
        </el-tab-pane>

        <!-- 增量 CRL -->
        <el-tab-pane label="增量 CRL（dCRL）" name="delta">
          <div class="table-toolbar">
            <div class="section-title">增量 CRL（RFC 5280 §5.2.4，自最近全量发布以来新增条目）</div>
            <div>
              <el-button :loading="deltaLoading" @click="loadDelta">刷新</el-button>
              <el-button type="primary" :loading="deltaDownLoading" :disabled="!delta.deltaPem" @click="onDownloadDelta">下载 dCRL</el-button>
            </div>
          </div>

          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="依赖方用 DeltaCRLIndicator 扩展将本增量与已缓存的全量 CRL 合并，实现小体积高频同步"
            style="margin-bottom: 12px"
          />

          <el-descriptions v-if="delta.deltaPem" :column="3" border size="small" style="margin-bottom: 12px">
            <el-descriptions-item label="本次更新（thisUpdate）">{{ delta.thisUpdate }}</el-descriptions-item>
            <el-descriptions-item label="下次更新（nextUpdate）">{{ delta.nextUpdate }}</el-descriptions-item>
            <el-descriptions-item label="增量条目数">{{ delta.entryCount }}</el-descriptions-item>
            <el-descriptions-item label="CRL 编号（crlNumber）">{{ delta.crlNumber }}</el-descriptions-item>
            <el-descriptions-item label="基础编号（baseCrlNumber）">
              <el-tag size="small" type="warning">{{ delta.baseCrlNumber }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else-if="!deltaLoading" description="暂无增量条目（自全量发布后无新增吊销/挂起）" />
          <el-input
            v-if="delta.deltaPem"
            :model-value="delta.deltaPem"
            type="textarea"
            :rows="10"
            readonly
            class="crl-preview"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getCrlView, downloadCrl, getDeltaCrl } from '@/api/system'
import { downloadText } from '@/lib/download'
import { currentOrg } from '@/lib/org'

// 全量/dCRL 均按机构域取数（api 缺省 orgKey=currentOrg()，页面随域切换重建）
const orgDomain = ref(currentOrg())

const activeTab = ref('full')
const loading = ref(false)
const crlLoading = ref(false)
const rows = ref([])
const crlPem = ref('')
const meta = ref({})

const deltaLoading = ref(false)
const deltaDownLoading = ref(false)
const deltaLoaded = ref(false)
const delta = ref({})

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
    meta.value = {
      issuerCn: res.data.issuerCn,
      thisUpdate: res.data.thisUpdate,
      nextUpdate: res.data.nextUpdate,
      entryCount: res.data.entryCount
    }
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function loadDelta() {
  deltaLoading.value = true
  try {
    const res = await getDeltaCrl()
    delta.value = res.data || {}
    deltaLoaded.value = true
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    deltaLoading.value = false
  }
}

function onTabChange(name) {
  if (name === 'delta' && !deltaLoaded.value) {
    loadDelta()
  }
}

async function onDownloadCrl() {
  crlLoading.value = true
  try {
    const pem = await downloadCrl(orgDomain.value)
    downloadText(pem, `csssl-crl-${orgDomain.value}.pem`)
    ElMessage.success('CRL已下载')
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    crlLoading.value = false
  }
}

async function onDownloadDelta() {
  deltaDownLoading.value = true
  try {
    downloadText(delta.value.deltaPem, `csssl-delta-crl-${orgDomain.value}.pem`)
    ElMessage.success('增量CRL已下载')
  } finally {
    deltaDownLoading.value = false
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
