<template>
  <div>
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form inline @keyup.enter="handleSearch">
          <el-form-item label="公司">
            <el-input v-model="query.keyword" clearable placeholder="公司名称 / 信用代码" style="width: 260px"/>
          </el-form-item>
          <el-form-item>
            <el-button :icon="Search" type="primary" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="list" :max-height="tableMaxHeight" stripe>
        <el-table-column prop="companyName" label="公司名称" min-width="200"/>
        <el-table-column prop="uscc" label="信用代码" min-width="180" class-name="cell-mono"/>
        <el-table-column label="根CA状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.caStatus?.initialized ? 'success' : 'info'">
              {{ row.caStatus?.initialized ? '已初始化' : '未初始化' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="根密钥状态" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="rootKeyTagType(row.caStatus?.rootKeyState)">
              {{ rootKeyLabel(row.caStatus?.rootKeyState) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SUB_CA状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.caStatus?.subCa" size="small" type="success">
              {{ row.caStatus.subCa.status }}
            </el-tag>
            <span v-else class="cell-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" align="right" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
                v-if="row.caStatus?.initialized"
                size="small"
                link
                type="primary"
                @click="onDownloadRoot(row)"
            >导出根证书
            </el-button>
            <el-button
                v-if="row.caStatus?.subCa"
                size="small"
                link
                type="primary"
                @click="onDownloadSubCa(row)"
            >导出SUB_CA
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!loading && list.length === 0" class="empty-tip">暂无匹配的公司</div>
      <div ref="footerRef" class="list-footer">
        <el-pagination
            v-model:current-page="pageNo"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="PAGE_SIZES"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="load"
            @current-change="load"
        />
      </div>
    </el-card>

    <!-- 详情弹框 -->
    <el-dialog v-model="detailVisible" :title="`根CA详情 — ${detailRow?.companyName || ''}`" width="780px" top="6vh">
      <div v-loading="detailLoading">
        <template v-if="detail.caStatus?.initialized">
          <div class="detail-section">
            <div class="detail-title">根CA证书</div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="证书CN">{{ detail.caStatus.cn }}</el-descriptions-item>
              <el-descriptions-item label="算法">
                <el-tag size="small">{{ detail.caStatus.algorithm }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="序列号" class-name="cell-mono">{{ detail.caStatus.serialHex }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ detail.caStatus.status }}</el-descriptions-item>
              <el-descriptions-item label="生效时间">{{ detail.caStatus.validFrom }}</el-descriptions-item>
              <el-descriptions-item label="过期时间">{{ detail.caStatus.validTo }}</el-descriptions-item>
            </el-descriptions>
          </div>
          <div v-if="detail.caStatus.subCa" class="detail-section">
            <div class="detail-title">服务签发二级CA（SUB_CA）</div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="证书CN">{{ detail.caStatus.subCa.cn }}</el-descriptions-item>
              <el-descriptions-item label="算法">
                <el-tag size="small">{{ detail.caStatus.subCa.algorithm }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="序列号" class-name="cell-mono">{{ detail.caStatus.subCa.serialHex }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ detail.caStatus.subCa.status }}</el-descriptions-item>
              <el-descriptions-item label="生效时间">{{ detail.caStatus.subCa.validFrom }}</el-descriptions-item>
              <el-descriptions-item label="过期时间">{{ detail.caStatus.subCa.validTo }}</el-descriptions-item>
            </el-descriptions>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="该公司尚未初始化根CA"/>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {Search, Refresh} from '@element-plus/icons-vue'
import {downloadRootCert, downloadSubCaCert, getCaStatus, listCompaniesCached} from '@/api/system'
import {downloadText} from '@/lib/download'
import {PAGE_SIZES, DEFAULT_PAGE_SIZE} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'

const queryCardRef = ref(null)
const footerRef = ref(null)
const {tableMaxHeight} = useTableMaxHeight(queryCardRef, footerRef)

const loading = ref(false)
const list = ref([])
const allCompanies = ref([])
const pageNo = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const query = ref({keyword: ''})

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailRow = ref(null)
const detail = ref({})

function rootKeyTagType(state) {
  if (state === 'SEALED') return 'warning'
  if (state === 'SHARES') return 'primary'
  if (state === 'MISSING') return 'danger'
  return 'success'
}
function rootKeyLabel(state) {
  if (state === 'SEALED') return '已封存'
  if (state === 'SHARES') return '门限分持'
  if (state === 'MISSING') return '缺失'
  if (state === 'ONLINE') return '在线'
  return '—'
}

async function load() {
  loading.value = true
  try {
    let filtered = allCompanies.value
    if (query.value.keyword) {
      const kw = query.value.keyword.trim().toLowerCase()
      filtered = filtered.filter(c =>
          (c.companyName || '').toLowerCase().includes(kw) ||
          (c.uscc || '').toLowerCase().includes(kw)
      )
    }
    total.value = filtered.length
    const start = (pageNo.value - 1) * pageSize.value
    list.value = filtered.slice(start, start + pageSize.value)
    // 并行拉每家公司的 CA 状态
    await Promise.all(list.value.map(async c => {
      try {
        const res = await getCaStatus(c.uscc)
        c.caStatus = res.data
      } catch {
        c.caStatus = null
      }
    }))
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pageNo.value = 1
  load()
}
function handleReset() {
  query.value = {keyword: ''}
  pageNo.value = 1
  load()
}

async function openDetail(row) {
  detailRow.value = row
  detail.value = {caStatus: row.caStatus}
  detailVisible.value = true
}

async function onDownloadRoot(row) {
  const pem = await downloadRootCert(row.uscc)
  downloadText(pem, `csssl-root-ca-${row.uscc}.pem`)
  ElMessage.success('根证书已导出')
}
async function onDownloadSubCa(row) {
  const pem = await downloadSubCaCert(row.uscc)
  downloadText(pem, `csssl-sub-ca-${row.uscc}.pem`)
  ElMessage.success('SUB_CA证书已导出')
}

onMounted(async () => {
  allCompanies.value = await listCompaniesCached()
  await load()
})
</script>

<style scoped>
.empty-tip {
  text-align: center;
  color: #94a3b8;
  padding: 32px 0;
  font-size: 13px;
}
.detail-section {
  margin-bottom: 16px;
}
.detail-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
  color: #1e293b;
}
</style>
