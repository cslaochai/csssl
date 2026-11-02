<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="审批状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
              <el-option label="待审批" value="PENDING"/>
              <el-option label="已执行" value="EXECUTED"/>
              <el-option label="已驳回" value="REJECTED"/>
              <el-option label="已撤销" value="CANCELLED"/>
            </el-select>
          </el-form-item>
          <el-form-item label="变更类型">
            <el-select v-model="query.opType" clearable placeholder="全部" style="width: 150px" @change="handleSearch">
              <el-option v-for="(label, code) in OP_TYPE_LABELS" :key="code" :label="label" :value="code"/>
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input
                v-model="query.keyword"
                clearable
                placeholder="单号 / 信用代码 / 公司名称"
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
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column class-name="cell-mono" label="单号" min-width="190" prop="opNo" show-overflow-tooltip/>
        <el-table-column label="变更类型" width="130">
          <template #default="{ row }">
            <el-tag :type="opTypeTag(row.opType)" size="small">{{ opTypeText(row.opType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="目标公司" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-mono">{{ row.companyUscc || '-' }}</span>
            <div v-if="row.companyName" class="cell-sub">{{ row.companyName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="变更事由" min-width="180" prop="reason" show-overflow-tooltip/>
        <el-table-column label="审批状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发起人" min-width="150">
          <template #default="{ row }">
            {{ row.requestedByName || row.requestedBy || '-' }}
            <div class="cell-sub">{{ row.requestedAt || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="审批人" min-width="150">
          <template #default="{ row }">
            {{ row.reviewedByName || '-' }}
            <div class="cell-sub">{{ row.reviewedAt || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="230">
          <template #default="{ row }">
            <el-button
                v-for="a in visibleRowActions(row)"
                :key="a.label"
                :disabled="a.disabled"
                :type="a.type || 'primary'"
                link
                size="small"
                @click="a.run()"
            >{{ a.label }}
            </el-button>
            <el-dropdown v-if="moreRowActions(row).length" trigger="click" @command="cmd => cmd.run()">
              <el-button link size="small" type="primary">
                更多
                <el-icon class="el-icon--right">
                  <ArrowDown/>
                </el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                      v-for="a in moreRowActions(row)"
                      :key="a.label"
                      :command="a"
                      :disabled="a.disabled"
                  >{{ a.label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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

    <!-- 接入凭证回执（仅展示一次） -->
    <el-dialog v-model="credVisible" :close-on-click-modal="false" title="接入凭证" width="620px" @closed="cred = null">
      <el-alert
          :closable="false"
          show-icon
          title="密钥仅展示一次，关闭后无法再次查看；请立即复制到 HIS 等外部系统的配置中。"
          type="warning"
      />
      <el-descriptions v-if="cred" :column="1" border class="cred-desc">
        <el-descriptions-item label="接入ID（App ID）">
          <span class="cell-mono">{{ cred.appId }}</span>
          <el-button link size="small" type="primary" @click="onCopy(cred.appId)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="接入密钥">
          <span class="cell-mono">{{ cred.secret }}</span>
          <el-button link size="small" type="primary" @click="onCopy(cred.secret)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="绑定公司">{{ cred.companyName || cred.companyUscc }}</el-descriptions-item>
        <el-descriptions-item label="密钥版本">{{ cred.secretVersion ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="credVisible = false">我已保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {ArrowDown, Refresh, Search} from '@element-plus/icons-vue'
import {approveCompanyOp, cancelCompanyOp, pageCompanyOps, rejectCompanyOp, takeCompanyOpReceipt} from '@/api/system'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'
import {hasPerm} from '@/lib/perm'
import {copyText} from '@/lib/clipboard'

// CREATE 仅历史单可查（新建公司已改为直接执行，不再走审批）
const OP_TYPE_LABELS = {
  CREATE: '新建公司',
  SUSPEND: '暂停公司',
  RESUME: '恢复公司',
  CLOSE: '关闭公司',
  RESET_CREDENTIAL: '重置接入密钥'
}

const username = ref(localStorage.getItem('username') || '')

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', opType: '', keyword: ''})
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

const credVisible = ref(false)
const cred = ref(null)

function opTypeText(t) {
  return OP_TYPE_LABELS[t] || t || '-'
}

function opTypeTag(t) {
  return {
    CREATE: 'primary',
    SUSPEND: 'warning',
    RESUME: 'success',
    CLOSE: 'danger',
    RESET_CREDENTIAL: 'warning'
  }[t] || 'info'
}

function statusText(s) {
  return {PENDING: '待审批', EXECUTED: '已执行', REJECTED: '已驳回', CANCELLED: '已撤销'}[s] || s || '-'
}

function statusTag(s) {
  return {PENDING: 'warning', EXECUTED: 'success', REJECTED: 'danger', CANCELLED: 'info'}[s] || 'info'
}

// 操作列口径：前 3 个平铺，其余收进「更多」下拉
const ACTION_VISIBLE = 3

// 审批（审批员）、撤回与领取回执（发起人本人）
function rowActions(row) {
  const acts = []
  if (row.status === 'PENDING' && hasPerm('company:op:approve')) {
    acts.push({label: '审批通过', type: 'success', run: () => onApprove(row)})
    acts.push({label: '驳回', type: 'danger', run: () => onReject(row)})
  }
  if (row.status === 'PENDING' && row.requestedBy === username.value) {
    acts.push({label: '撤回', type: 'info', run: () => onCancel(row)})
  }
  const receiptOp = row.opType === 'CREATE' || row.opType === 'RESET_CREDENTIAL'
  if (row.status === 'EXECUTED' && receiptOp && !row.receiptTakenAt && row.requestedBy === username.value) {
    acts.push({label: '领取接入凭证', type: 'primary', run: () => onTakeReceipt(row)})
  }
  return acts
}

function visibleRowActions(row) {
  return rowActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return rowActions(row).slice(ACTION_VISIBLE)
}

async function onCopy(text) {
  const ok = await copyText(text)
  ok ? ElMessage.success('已复制') : ElMessage.error('复制失败，请手动选择复制')
}

function showCredential(payload) {
  cred.value = {
    appId: payload.appId,
    secret: payload.secret,
    companyUscc: payload.companyUscc,
    companyName: payload.companyName,
    secretVersion: payload.secretVersion
  }
  credVisible.value = true
}

async function load() {
  loading.value = true
  try {
    const res = await pageCompanyOps({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      status: query.status || undefined,
      opType: query.opType || undefined,
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

function handleSearch() {
  query.pageNo = 1
  load()
}

function handleReset() {
  query.status = ''
  query.opType = ''
  query.keyword = ''
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

async function onApprove(row) {
  const {value} = await ElMessageBox.prompt(
      `批准后将立即执行「${opTypeText(row.opType)}」（目标 ${row.companyUscc}），不可撤回。审批意见可留空。`,
      '审批通过',
      {type: 'warning', confirmButtonText: '批准并执行', cancelButtonText: '取消', inputPlaceholder: '审批意见（选填）'}
  )
  await approveCompanyOp(row.id, String(value || '').trim() || undefined)
  ElMessage.success('已批准并执行')
  await load()
}

async function onReject(row) {
  const {value} = await ElMessageBox.prompt(
      `驳回「${opTypeText(row.opType)}」（目标 ${row.companyUscc}），审批意见必填。`,
      '驳回变更单',
      {
        type: 'error',
        confirmButtonText: '确定驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回原因（必填）',
        inputValidator: v => (String(v || '').trim() ? true : '驳回必须填写审批意见')
      }
  )
  await rejectCompanyOp(row.id, String(value).trim())
  ElMessage.success('已驳回')
  await load()
}

async function onCancel(row) {
  await ElMessageBox.confirm(
      `撤回「${opTypeText(row.opType)}」变更单（${row.opNo}）？撤回后需重新发起。`,
      '撤回变更单',
      {type: 'warning', confirmButtonText: '确定撤回', cancelButtonText: '取消'}
  )
  await cancelCompanyOp(row.id)
  ElMessage.success('已撤回')
  await load()
}

async function onTakeReceipt(row) {
  await ElMessageBox.confirm(
      '接入密钥仅展示一次，领取后无法再次查看；关闭弹窗后如未保存，只能重新发起重置变更单。',
      '领取接入凭证',
      {type: 'warning', confirmButtonText: '领取', cancelButtonText: '取消'}
  )
  const res = await takeCompanyOpReceipt(row.id)
  showCredential(res.data || {})
  await load()
}

onMounted(load)
</script>

<style scoped>
.cred-desc {
  margin-top: 12px;
}
</style>
