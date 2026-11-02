<template>
  <div>
    <el-tabs v-model="activeTab" class="crl-tabs" @tab-change="onTabChange">
      <!-- 全量 CRL -->
      <el-tab-pane label="全量 CRL" name="full">
        <!-- 查询卡 -->
        <el-card ref="queryCardRef" class="query-card" shadow="never">
          <div class="query-wrap">
            <el-form :model="query" inline>
              <el-form-item label="公司">
                <el-select v-model="companyDomain" placeholder="选择公司" style="width: 300px"
                           @change="onCompanyChange">
                  <el-option
                      v-for="o in companyOptions"
                      :key="o.uscc"
                      :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                      :value="o.uscc"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="状态">
                <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px"
                           @change="handleSearch">
                  <el-option label="已吊销" value="REVOKED"/>
                  <el-option label="已挂起" value="FROZEN"/>
                </el-select>
              </el-form-item>
              <el-form-item label="吊销原因">
                <el-select v-model="query.reason" clearable placeholder="全部" style="width: 160px"
                           @change="handleSearch">
                  <el-option v-for="(label, code) in reasonMap" :key="code" :label="label" :value="code"/>
                </el-select>
              </el-form-item>
              <el-form-item label="关键字">
                <el-input
                    v-model="query.keyword"
                    clearable
                    placeholder="CN / 序列号"
                    style="width: 220px"
                    @clear="handleSearch"
                    @keyup.enter="handleSearch"
                />
              </el-form-item>
              <el-form-item>
                <el-button :icon="Search" type="primary" @click="handleSearch">搜索</el-button>
                <el-button :icon="Refresh" @click="handleReset">重置</el-button>
              </el-form-item>
            </el-form>
            <div class="query-actions">
              <el-button v-perm="'crl:download'" :icon="Download" :loading="crlLoading" type="primary"
                         @click="onDownloadCrl">下载 CRL
              </el-button>
            </div>
          </div>
        </el-card>

        <!-- 表格卡 -->
        <el-card class="table-card" shadow="never">
          <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
            <el-table-column label="主体CN" min-width="180" prop="subjectCn" show-overflow-tooltip/>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ row.certType }}</template>
            </el-table-column>
            <el-table-column class-name="cell-mono" label="序列号" min-width="160" prop="serialHex"
                             show-overflow-tooltip/>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'REVOKED' ? 'danger' : 'warning'" size="small">
                  {{ row.status === 'REVOKED' ? '已吊销' : '已挂起' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="吊销原因" min-width="140">
              <template #default="{ row }">{{ reasonText(row.revokeReason) }}</template>
            </el-table-column>
            <el-table-column label="吊销时间" prop="revokedAt" width="170"/>
            <el-table-column align="right" fixed="right" label="操作" width="90">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="onRowDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
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
              :rows="10"
              class="crl-preview"
              readonly
              type="textarea"
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
              <el-button v-perm="'crl:download'" :disabled="!delta.deltaPem" :loading="deltaDownLoading" type="primary"
                         @click="onDownloadDelta">下载 dCRL
              </el-button>
            </div>
          </div>

          <el-descriptions v-if="delta.deltaPem" :column="3" border size="small" style="margin-bottom: 12px">
            <el-descriptions-item label="本次更新（thisUpdate）">{{ delta.thisUpdate }}</el-descriptions-item>
            <el-descriptions-item label="下次更新（nextUpdate）">{{ delta.nextUpdate }}</el-descriptions-item>
            <el-descriptions-item label="增量条目数">{{ delta.entryCount }}</el-descriptions-item>
            <el-descriptions-item label="吊销列表编号（crlNumber）">{{ delta.crlNumber }}</el-descriptions-item>
            <el-descriptions-item label="基础编号（baseCrlNumber）">
              <el-tag size="small" type="warning">{{ delta.baseCrlNumber }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
          <el-input
              v-if="delta.deltaPem"
              :model-value="delta.deltaPem"
              :rows="10"
              class="crl-preview"
              readonly
              type="textarea"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 吊销记录详情（行点击） -->
    <el-dialog v-model="detailVisible" title="吊销记录详情" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="序列号"><span class="mono-wrap">{{ detail?.serialHex }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="主体CN">{{ detail?.subjectCn }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detail?.certType }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{
            detail?.status === 'REVOKED' ? '已吊销' : '已挂起'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="吊销原因">{{
            detail ? reasonText(detail.revokeReason) : ''
          }}
        </el-descriptions-item>
        <el-descriptions-item label="吊销时间">{{ detail?.revokedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {Download, Refresh, Search} from '@element-plus/icons-vue'
import {downloadCrl, getCrlView, getDeltaCrl, listCompaniesCached, pageCrlRecords} from '@/api/system'
import {downloadText} from '@/lib/download'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'

// CRL 按公司独立发布：页面内显式选择公司域
const companyDomain = ref('')
const companyOptions = ref([])

const activeTab = ref('full')
const loading = ref(false)
const crlLoading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', reason: '', keyword: ''})
const crlPem = ref('')
const meta = ref({})
const detailVisible = ref(false)
const detail = ref(null)

// 两卡式列表页：表格只设最大高度，超高内部滚动
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

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

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
}

async function loadRecords() {
  loading.value = true
  try {
    const res = await pageCrlRecords({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      status: query.status || undefined,
      reason: query.reason || undefined,
      keyword: query.keyword.trim() || undefined,
      companyUscc: companyDomain.value || undefined
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
    const res = await getCrlView(companyDomain.value)
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
    const res = await getDeltaCrl(companyDomain.value)
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
    const pem = await downloadCrl(companyDomain.value)
    downloadText(pem, `csssl-crl-${companyDomain.value}.pem`)
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
    downloadText(delta.value.deltaPem, `csssl-delta-crl-${companyDomain.value}.pem`)
    ElMessage.success('增量CRL已下载')
  } finally {
    deltaDownLoading.value = false
  }
}

async function onCompanyChange() {
  query.pageNo = 1
  crlPem.value = ''
  meta.value = {}
  delta.value = {}
  deltaLoaded.value = false
  await Promise.all([loadRecords(), loadView()])
  if (activeTab.value === 'delta') await loadDelta()
}

onMounted(async () => {
  companyOptions.value = await listCompaniesCached()
  const active = companyOptions.value.find(t => t.status === 'ACTIVE') || companyOptions.value[0]
  companyDomain.value = active?.uscc || ''
  if (companyDomain.value) {
    await Promise.all([loadRecords(), loadView()])
  }
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

.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
