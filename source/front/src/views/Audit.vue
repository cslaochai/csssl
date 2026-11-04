<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="公司">
            <el-select v-model="companyDomain" clearable placeholder="全部" style="width: 300px" @change="handleSearch">
              <el-option
                  v-for="o in companyOptions"
                  :key="o.uscc"
                  :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                  :value="o.uscc"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="操作类型">
            <el-select v-model="action" clearable placeholder="全部" style="width: 160px" @change="handleSearch">
              <el-option v-for="item in actions" :key="item.value" :label="item.label" :value="item.value"/>
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button :icon="Search" type="primary" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
        <div class="query-actions">
          <el-button v-perm="'audit:chain'" plain type="warning" @click="openChain">链对账</el-button>
          <el-button v-perm="'audit:export'" :loading="exporting" plain type="primary" @click="onExport">导出归档CSV
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column label="操作人" width="130">
          <template #default="{ row }">{{ row.realName || '无' }}</template>
        </el-table-column>
        <el-table-column label="公司" width="110">
          <template #default="{ row }">
            <el-tag v-if="!row.companyUscc" size="small" type="info">全局</el-tag>
            <span v-else class="mono-cell">{{ companyNames[row.companyUscc] || row.companyUscc }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">{{ actionText(row.action) }}</template>
        </el-table-column>
        <el-table-column label="对象" prop="target" width="120"/>
        <el-table-column label="详情" min-width="220" prop="detail" show-overflow-tooltip/>
        <el-table-column label="来源IP" prop="ip" width="140"/>
        <el-table-column label="时间" prop="createdAt" width="170"/>
        <el-table-column align="right" fixed="right" label="操作" width="200">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="onRowDetail(row)">详情</el-button>
            <el-button v-perm="'audit:verify'" link size="small" type="primary" @click="onVerify(row)">验签</el-button>
            <el-tag v-if="row.sign" size="small" type="success">已签名</el-tag>
            <el-tag v-else size="small" type="info">无签名</el-tag>
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

    <!-- 审计详情（行点击） -->
    <el-dialog v-model="detailVisible" title="审计记录详情" width="900px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="时间">{{ detail?.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail?.realName || '无' }}</el-descriptions-item>
        <el-descriptions-item label="公司">{{
            companyNames[detail?.companyUscc] || detail?.companyUscc || '全局'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="操作类型">{{ detail ? actionText(detail.action) : '' }}</el-descriptions-item>
        <el-descriptions-item label="对象">{{ detail?.target || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源IP">{{ detail?.ip || '-' }}</el-descriptions-item>
        <el-descriptions-item label="详情" :span="2">
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
          <el-button v-perm="'audit:verify'" plain type="primary" @click="onVerifyFromDetail">验签</el-button>
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="verifyVisible" title="审计验签（GM/T 0034 §10.1/§10.5 防篡改校验）" width="900px">
      <el-result v-if="verify?.valid" icon="success" sub-title="记录未被篡改，由审计签名密钥签发，哈希链指向的记录签名一致"
                 title="签名有效"/>
      <el-result v-else-if="verify" icon="error" sub-title="记录内容已被改动或签名缺失" title="签名校验失败"/>
      <div v-else style="padding: 20px; text-align: center; color: #909399;">验签中…</div>
      <el-descriptions v-if="verify" :column="2" border>
        <el-descriptions-item label="记录ID">{{ verify.id }}</el-descriptions-item>
        <el-descriptions-item label="规范化串（canonical）" :span="2">
          <div class="mono-wrap">{{ verify.canonical }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="签名值（SM3withSM2 hex）" :span="2">
          <div class="mono-wrap">{{ verify.sign }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="哈希链上一签名" :span="2">
          <div class="mono-wrap">{{ verify.prevSign || '（无，首条/历史记录）' }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <el-dialog v-model="chainVisible" title="审计哈希链对账（GM/T 0034 §10.5 链式完整性校验）" width="640px">
      <p style="margin-bottom: 12px; color: #606266; font-size: 13px">
        对指定 ID 区间（可空，默认全域）逐条重算规范化哈希并验证签名链，检出被增删改动的记录。
      </p>
      <el-alert
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="链为全局构造（跨公司连续），对账为跨公司视角——属合规设计：审计链完整性覆盖所有公司域记录，不随公司域切换分段。"
          type="info"
      />
      <el-form inline>
        <el-form-item label="起始ID">
          <el-input-number v-model="chainFrom" :controls="false" :min="1" placeholder="可空" style="width: 120px"/>
        </el-form-item>
        <el-form-item label="截止ID">
          <el-input-number v-model="chainTo" :controls="false" :min="1" placeholder="可空" style="width: 120px"/>
        </el-form-item>
        <el-form-item>
          <el-button :loading="chainLoading" type="primary" @click="onChainVerify">开始对账</el-button>
        </el-form-item>
      </el-form>

      <template v-if="chainResult">
        <el-result
            v-if="chainResult.broken.length === 0"
            :title="`链完整：共对账 ${chainResult.checked} 条，未发现断裂`"
            icon="success"
            sub-title="该区间内每条记录的签名与哈希链上一签名均校验通过，审计数据未被篡改"
        />
        <template v-else>
          <el-alert
              :closable="false"
              :title="`共对账 ${chainResult.checked} 条，检出 ${chainResult.broken.length} 条断裂`"
              show-icon
              style="margin-bottom: 12px"
              type="error"
          />
          <el-table :data="chainResult.broken" border max-height="280" stripe>
            <el-table-column label="断裂记录ID" prop="id" width="130"/>
            <el-table-column label="原因" min-width="260" prop="reason" show-overflow-tooltip/>
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
      <el-result :sub-title="`共 ${exportCount} 条审计记录（UTF-8 BOM，Excel 可直接打开）`" icon="success"
                 title="CSV 已下载"/>
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
import {onMounted, reactive, ref} from 'vue'
import {Refresh, Search} from '@element-plus/icons-vue'
import {chainVerify, companyNameMap, exportAuditCsv, listCompaniesCached, pageAudit, verifyAuditLog} from '@/api/system'
import {downloadText} from '@/lib/download'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'

// 审计为监督职能：公司筛选可留空=跨公司全量只读
const companyDomain = ref('')
const companyOptions = ref([])
const companyNames = ref({})

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const action = ref('')
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE})

// 两卡式列表页：表格只设最大高度，超高内部滚动
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()
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
  {value: 'LOGIN', label: '登录'},
  {value: 'LOGOUT', label: '退出'},
  {value: 'CA_INIT', label: '初始化根CA'},
  {value: 'CA_SEAL', label: '封存根私钥'},
  {value: 'CA_UNSEAL', label: '解封根私钥'},
  {value: 'CA_SHARE_GENERATE', label: '门限分持生成'},
  {value: 'CA_SHARE_DOWNLOAD', label: '导出门限份额'},
  {value: 'CA_UNSEAL_SHARES', label: '份额解封根私钥'},
  {value: 'CERT_ISSUE', label: '签发证书'},
  {value: 'CERT_CSR_ISSUE', label: 'CSR签发'},
  {value: 'CERT_PUBKEY_ISSUE', label: '公钥签发'},
  {value: 'CERT_REVOKE', label: '吊销证书'},
  {value: 'CERT_SUSPEND', label: '挂起证书'},
  {value: 'CERT_UNSUSPEND', label: '解除挂起'},
  {value: 'CERT_RENEW', label: '证书更新'},
  {value: 'CERT_REKEY', label: '密钥更新'},
  {value: 'CERT_DOWNLOAD', label: '下载证书/私钥'},
  {value: 'CRL_DOWNLOAD', label: '下载CRL'},
  {value: 'RA_SUBMIT', label: 'RA提交申请'},
  {value: 'RA_APPROVE', label: 'RA批准'},
  {value: 'RA_REJECT', label: 'RA驳回'},
  {value: 'RA_ISSUE', label: 'RA签发'},
  {value: 'RA_CANCEL', label: 'RA撤销'},
  {value: 'TSA_STAMP', label: '时间戳盖章'},
  {value: 'USER_CREATE', label: '创建账号'},
  {value: 'USER_UPDATE', label: '更新账号'},
  {value: 'USER_RESET_PWD', label: '重置口令'},
  {value: 'PWD_CHANGE', label: '修改口令'},
  {value: 'TEMPLATE_CREATE', label: '创建模板'},
  {value: 'TEMPLATE_UPDATE', label: '更新模板'},
  {value: 'TEMPLATE_DELETE', label: '删除模板'},
  {value: 'COMPANY_OP_SUBMIT', label: '发起公司变更单'},
  {value: 'COMPANY_OP_APPROVE', label: '批准公司变更单'},
  {value: 'COMPANY_OP_REJECT', label: '驳回公司变更单'},
  {value: 'COMPANY_OP_CANCEL', label: '撤回公司变更单'},
  {value: 'COMPANY_OP_RECEIPT', label: '领取接入凭证回执'},
  {value: 'COMPANY_CREATE', label: '新建公司'},
  {value: 'COMPANY_SUSPEND', label: '暂停公司（执行）'},
  {value: 'COMPANY_RESUME', label: '恢复公司（执行）'},
  {value: 'COMPANY_CLOSE', label: '关闭公司（执行）'},
  {value: 'COMPANY_CREDENTIAL_RESET', label: '重置接入密钥（执行）'}
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
  if (id) onVerify({id})
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
    const res = await exportAuditCsv(companyDomain.value || undefined)
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
    chainResult.value = {checked: res.data.checked ?? 0, broken: res.data.broken || []}
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
      action: action.value || undefined,
      companyUscc: companyDomain.value || undefined
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
  companyDomain.value = ''
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

onMounted(async () => {
  load()
  companyNames.value = await companyNameMap()
  companyOptions.value = await listCompaniesCached()
})
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
