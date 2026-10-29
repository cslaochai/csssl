<template>
  <div>
    <el-tabs v-model="activeTab" class="crl-tabs" @tab-change="onTabChange">
      <!-- 全量 CRL -->
      <el-tab-pane label="全量 CRL" name="full">
        <!-- 查询卡 -->
        <el-card ref="queryCardRef" class="query-card" shadow="never">
          <div class="query-wrap">
            <el-form :model="query" inline>
              <el-form-item label="状态">
                <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px" @change="handleSearch">
                  <el-option label="已吊销" value="REVOKED" />
                  <el-option label="已挂起" value="FROZEN" />
                </el-select>
              </el-form-item>
              <el-form-item label="吊销原因">
                <el-select v-model="query.reason" clearable placeholder="全部" style="width: 160px" @change="handleSearch">
                  <el-option v-for="(label, code) in reasonMap" :key="code" :label="label" :value="code" />
                </el-select>
              </el-form-item>
              <el-form-item label="关键字">
                <el-input
                  v-model="query.keyword"
                  placeholder="CN / 序列号"
                  clearable
                  style="width: 220px"
                  @keyup.enter="handleSearch"
                  @clear="handleSearch"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
                <el-button :icon="Refresh" @click="handleReset">重置</el-button>
              </el-form-item>
            </el-form>
            <div class="query-actions">
              <el-tag size="small" type="info" effect="plain">当前域：{{ orgDomain }}</el-tag>
              <el-button v-perm="'crl:download'" type="primary" :icon="Download" :loading="crlLoading" @click="onDownloadCrl">下载 CRL</el-button>
            </div>
          </div>
        </el-card>

        <!-- 表格卡 -->
        <el-card class="table-card" shadow="never">
          <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
            <el-table-column prop="id" label="ID" width="70" class-name="cell-mono" />
            <el-table-column prop="subjectCn" label="主体CN" min-width="180" show-overflow-tooltip />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ row.certType }}</template>
            </el-table-column>
            <el-table-column prop="serialHex" label="序列号" class-name="cell-mono" min-width="160" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'REVOKED' ? 'danger' : 'warning'">{{ row.status === 'REVOKED' ? '已吊销' : '已挂起' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="吊销原因" min-width="140">
              <template #default="{ row }">{{ reasonText(row.revokeReason) }}</template>
            </el-table-column>
            <el-table-column prop="revokedAt" label="吊销时间" width="170" />
          </el-table>
          <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的吊销记录</div>
          <div ref="footerRef" class="list-footer">
            <el-pagination
              v-model:current-page="query.pageNo"
              v-model:page-size="query.pageSize"
              :page-sizes="PAGE_SIZES"
              :total="total"
              layout="total, sizes, prev, pager, next, jumper"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </el-card>

        <!-- CRL 正文预览：已发布全量内容，不随表格筛选变化 -->
        <el-card v-if="crlPem" class="detail-card" shadow="never">
          <div class="detail-title">CRL 内容</div>
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
        </el-card>
      </el-tab-pane>

      <!-- 增量 CRL -->
      <el-tab-pane label="增量 CRL" name="delta">
        <div class="page-card">
          <div class="table-toolbar">
            <div class="section-title">增量 CRL</div>
            <div>
              <el-button :loading="deltaLoading" @click="loadDelta">刷新</el-button>
              <el-button v-perm="'crl:download'" type="primary" :loading="deltaDownLoading" :disabled="!delta.deltaPem" @click="onDownloadDelta">下载 dCRL</el-button>
            </div>
          </div>

          <el-descriptions v-if="delta.deltaPem" :column="3" border size="small" style="margin-bottom: 12px">
            <el-descriptions-item label="本次更新（thisUpdate）">{{ delta.thisUpdate }}</el-descriptions-item>
            <el-descriptions-item label="下次更新（nextUpdate）">{{ delta.nextUpdate }}</el-descriptions-item>
            <el-descriptions-item label="增量条目数">{{ delta.entryCount }}</el-descriptions-item>
            <el-descriptions-item label="CRL 编号（crlNumber）">{{ delta.crlNumber }}</el-descriptions-item>
            <el-descriptions-item label="基础编号（baseCrlNumber）">
              <el-tag size="small" type="warning">{{ delta.baseCrlNumber }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <div v-else-if="!deltaLoading" class="empty-tip">暂无增量条目</div>
          <el-input
            v-if="delta.deltaPem"
            :model-value="delta.deltaPem"
            type="textarea"
            :rows="10"
            readonly
            class="crl-preview"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import { getCrlView, pageCrlRecords, downloadCrl, getDeltaCrl } from '@/api/system'
import { downloadText } from '@/lib/download'
import { currentOrg } from '@/lib/org'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'

// 全量/dCRL 均按机构域取数（api 缺省 orgKey=currentOrg()，页面随域切换重建）
const orgDomain = ref(currentOrg())

const activeTab = ref('full')
const loading = ref(false)
const crlLoading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', reason: '', keyword: '' })
const crlPem = ref('')
const meta = ref({})

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()

const deltaLoading = ref(false)
const deltaDownLoading = ref(false)
const deltaLoaded = ref(false)
const delta = ref({})

const reasonMap = {
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

function reasonText(r) {
  return reasonMap[r] || r || '未指定'
}

async function loadRecords() {
  loading.value = true
  try {
    const res = await pageCrlRecords({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      status: query.status || undefined,
      reason: query.reason || undefined,
      keyword: query.keyword.trim() || undefined
    })
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function loadView() {
  try {
    const res = await getCrlView()
    crlPem.value = res.data.crlPem || ''
    meta.value = {
      issuerCn: res.data.issuerCn,
      thisUpdate: res.data.thisUpdate,
      nextUpdate: res.data.nextUpdate,
      entryCount: res.data.entryCount
    }
  } catch (e) {
    // 错误提示已由拦截器统一处理
  }
}

function handleSearch() {
  query.pageNo = 1
  loadRecords()
}

function handleReset() {
  query.status = ''
  query.reason = ''
  query.keyword = ''
  handleSearch()
}

function handleSizeChange(val) {
  query.pageSize = val
  query.pageNo = 1
  loadRecords()
}

function handleCurrentChange(val) {
  query.pageNo = val
  loadRecords()
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

onMounted(() => {
  loadRecords()
  loadView()
})
</script>

<style scoped>
.crl-tabs :deep(.el-tabs__header) {
  background: #fff;
  border-radius: 6px;
  padding: 0 16px;
  margin-bottom: 12px;
}
.detail-card {
  margin-top: 12px;
  border-radius: 6px;
}
.detail-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title);
}
.crl-preview {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title);
}
</style>
