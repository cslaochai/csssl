<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="操作类型">
            <el-select v-model="action" placeholder="全部" clearable style="width: 160px" @change="handleSearch">
              <el-option v-for="item in actions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
        <div class="query-actions">
          <el-tag size="small" type="info" effect="plain">当前域：{{ orgDomain }}</el-tag>
          <el-button v-perm="'audit:chain'" type="warning" plain @click="openChain">链对账</el-button>
          <el-button v-perm="'audit:export'" type="primary" plain :loading="exporting" @click="onExport">导出归档CSV</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="username" label="操作人" width="130" />
        <el-table-column label="机构" width="110">
          <template #default="{ row }">
            <el-tag v-if="!row.orgKey" size="small" type="info">全局</el-tag>
            <span v-else class="mono-cell">{{ row.orgKey }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">{{ actionText(row.action) }}</template>
        </el-table-column>
        <el-table-column prop="target" label="对象" width="120" />
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column prop="ip" label="来源IP" width="140" />
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column align="right" label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onRowDetail(row)">详情</el-button>
            <el-button v-perm="'audit:verify'" link type="primary" size="small" @click="onVerify(row)">验签</el-button>
            <el-tag v-if="row.sign" size="small" type="success">已签名</el-tag>
            <el-tag v-else size="small" type="info">无签名</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的审计记录</div>
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

    <!-- 审计详情（行点击） -->
    <el-dialog v-model="detailVisible" title="审计记录详情" width="640px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="时间">{{ detail?.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail?.username }}</el-descriptions-item>
        <el-descriptions-item label="机构">{{ detail?.orgKey || '全局' }}</el-descriptions-item>
        <el-descriptions-item label="操作类型">{{ detail ? actionText(detail.action) : '' }}</el-descriptions-item>
        <el-descriptions-item label="对象">{{ detail?.target || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源IP">{{ detail?.ip || '-' }}</el-descriptions-item>
        <el-descriptions-item label="详情">
          <div class="mono-wrap">{{ detail?.detail || '-' }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="防篡改签名">
          <el-tag v-if="detail?.sign" size="small" type="success">已签名</el-tag>
          <el-tag v-else size="small" type="info">无签名</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="哈希链上一条">{{ detail?.prevId || '（首条）' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <div class="dialog-footer">
          <el-button v-perm="'audit:verify'" type="primary" plain @click="onVerifyFromDetail">验签</el-button>
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

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

    <el-dialog v-model="chainVisible" title="审计哈希链对账（GM/T 0034 §10.5 链式完整性校验）" width="640px">
      <p style="margin-bottom: 12px; color: #606266; font-size: 13px">
        对指定 ID 区间（可空，默认全域）逐条重算规范化哈希并验证签名链，检出被增删改动的记录。
      </p>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="链为全局构造（跨机构连续），对账为跨机构视角——属合规设计：审计链完整性覆盖所有机构域记录，不随机构域切换分段。"
        style="margin-bottom: 12px"
      />
      <el-form inline>
        <el-form-item label="起始ID">
          <el-input-number v-model="chainFrom" :min="1" :controls="false" placeholder="可空" style="width: 120px" />
        </el-form-item>
        <el-form-item label="截止ID">
          <el-input-number v-model="chainTo" :min="1" :controls="false" placeholder="可空" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="chainLoading" @click="onChainVerify">开始对账</el-button>
        </el-form-item>
      </el-form>

      <template v-if="chainResult">
        <el-result
          v-if="chainResult.broken.length === 0"
          icon="success"
          :title="`链完整：共对账 ${chainResult.checked} 条，未发现断裂`"
          sub-title="该区间内每条记录的签名与哈希链上一签名均校验通过，审计数据未被篡改"
        />
        <template v-else>
          <el-alert
            type="error"
            :closable="false"
            show-icon
            :title="`共对账 ${chainResult.checked} 条，检出 ${chainResult.broken.length} 条断裂`"
            style="margin-bottom: 12px"
          />
          <el-table :data="chainResult.broken" border stripe max-height="280">
            <el-table-column prop="id" label="断裂记录ID" width="130" />
            <el-table-column prop="reason" label="原因" min-width="260" show-overflow-tooltip />
          </el-table>
        </template>
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="chainVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="exportVisible" title="审计归档导出完成（留存 ≥5 年材料）" width="560px">
      <el-result icon="success" title="CSV 已下载" :sub-title="`共 ${exportCount} 条审计记录（UTF-8 BOM，Excel 可直接打开）`" />
      <p style="font-size: 13px; color: #606266; margin-bottom: 8px">
        归档报告完整性签名（SM3withSM2，对 CSV 全文计算）——请与 CSV 一并保存，事后校验：对同一 CSV 内容重算比对一致即未被篡改。
      </p>
      <div class="mono-wrap">{{ exportSign || '（签名生成失败，见服务端日志）' }}</div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="exportVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { pageAudit, verifyAuditLog, exportAuditCsv, chainVerify } from '@/api/system'
import { downloadText } from '@/lib/download'
import { currentOrg } from '@/lib/org'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'

// 列表/导出/对账请求由 request 封装自动附带 orgKey=currentOrg()（机构域过滤）
const orgDomain = ref(currentOrg())

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const action = ref('')
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE })

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()
const verifyVisible = ref(false)
const verify = ref(null)
const detailVisible = ref(false)
const detail = ref(null)
const exporting = ref(false)
const exportVisible = ref(false)
const exportSign = ref('')
const exportCount = ref(0)

// 链对账
const chainVisible = ref(false)
const chainLoading = ref(false)
const chainFrom = ref(null)
const chainTo = ref(null)
const chainResult = ref(null)

const actions = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '退出' },
  { value: 'CA_INIT', label: '初始化根CA' },
  { value: 'CA_SEAL', label: '封存根私钥' },
  { value: 'CA_UNSEAL', label: '解封根私钥' },
  { value: 'CA_SHARE_GENERATE', label: '门限分持生成' },
  { value: 'CA_SHARE_DOWNLOAD', label: '导出门限份额' },
  { value: 'CA_UNSEAL_SHARES', label: '份额解封根私钥' },
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
  { value: 'TSA_STAMP', label: '时间戳盖章' },
  { value: 'USER_CREATE', label: '创建账号' },
  { value: 'USER_UPDATE', label: '更新账号' },
  { value: 'USER_RESET_PWD', label: '重置口令' },
  { value: 'PWD_CHANGE', label: '修改口令' },
  { value: 'TEMPLATE_CREATE', label: '创建模板' },
  { value: 'TEMPLATE_UPDATE', label: '更新模板' },
  { value: 'TEMPLATE_DELETE', label: '删除模板' },
  { value: 'ORG_CREATE', label: '创建机构' },
  { value: 'ORG_SUSPEND', label: '暂停机构' },
  { value: 'ORG_RESUME', label: '恢复机构' },
  { value: 'ORG_CLOSE', label: '关闭机构' }
]

function actionText(a) {
  return actions.find(i => i.value === a)?.label || a
}

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
}

function onVerifyFromDetail() {
  const id = detail.value?.id
  detailVisible.value = false
  if (id) onVerify({ id })
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

async function onExport() {
  exporting.value = true
  try {
    const res = await exportAuditCsv()
    const data = res.data
    downloadText(data.csv, `audit-export-${new Date().toISOString().slice(0, 10)}.csv`)
    exportSign.value = data.sign
    exportCount.value = data.count
    exportVisible.value = true
  } finally {
    exporting.value = false
  }
}

function openChain() {
  chainFrom.value = null
  chainTo.value = null
  chainResult.value = null
  chainVisible.value = true
}

async function onChainVerify() {
  chainLoading.value = true
  try {
    const res = await chainVerify(chainFrom.value || undefined, chainTo.value || undefined)
    chainResult.value = { checked: res.data.checked ?? 0, broken: res.data.broken || [] }
  } finally {
    chainLoading.value = false
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
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNo = 1
  load()
}

function handleReset() {
  action.value = ''
  handleSearch()
}

function handleSizeChange(val) {
  query.pageSize = val
  query.pageNo = 1
  load()
}

function handleCurrentChange(val) {
  query.pageNo = val
  load()
}

onMounted(load)
</script>

<style scoped>
.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
  max-height: 120px;
  overflow-y: auto;
  white-space: pre-wrap;
}
.mono-cell {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}
</style>
