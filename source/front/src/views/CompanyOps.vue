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
        <el-table-column label="保管人投票" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.opType === 'REASSIGN_HOLDERS'">
              <span v-if="row.status === 'PENDING'">待投票：同意 {{ row.agreeCount ?? 0 }}/{{ row.voteRequired ?? 4 }} · 驳回 {{ row.rejectCount ?? 0 }}</span>
              <span v-else>{{ row.agreeCount ?? 0 }} 同意 / {{ row.rejectCount ?? 0 }} 驳回 · {{ statusText(row.status) }}</span>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="发起人" min-width="150">
          <template #default="{ row }">
            {{ row.requestedByName || '无' }}
            <div class="cell-sub">{{ row.requestedAt || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="被指派审批人" min-width="140">
          <template #default="{ row }">
            <span v-if="row.opType === 'REASSIGN_HOLDERS'">—（原保管人投票）</span>
            <span v-else>{{ row.assigneeRealName || '未指派' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="审批人" min-width="150">
          <template #default="{ row }">
            {{ row.reviewedByName || '-' }}
            <div class="cell-sub">{{ row.reviewedAt || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="300">
          <template #default="{ row }">
            <el-button
                v-for="a in rowActions(row)"
                :key="a.label"
                :disabled="a.disabled"
                :type="a.type || 'primary'"
                link
                size="small"
                @click="a.run()"
            >{{ a.label }}
            </el-button>
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
      <el-descriptions v-if="cred" :column="2" border class="cred-desc">
        <el-descriptions-item label="接入ID（App ID）" :span="2">
          <span class="cell-mono">{{ cred.appId }}</span>
          <el-button link size="small" type="primary" @click="onCopy(cred.appId)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="接入密钥" :span="2">
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

    <!-- 保管人投票明细（重新指派保管人单） -->
    <el-dialog v-model="voteVisible" :close-on-click-modal="false" :title="`保管人投票明细：${voteTicket?.opNo || ''}`" width="680px">
      <el-alert v-if="voteTicket" :closable="false" class="dialog-tip" show-icon type="info"
                :title="`由该公司原 5 名密钥保管人投票，同意数须超过 3（≥${voteTicket.voteRequired ?? 4}）才执行，驳回达到 2 即否决，不经过公司审批员。当前：同意 ${voteTicket.agreeCount ?? 0} / 驳回 ${voteTicket.rejectCount ?? 0}`"/>
      <el-table :data="voteTicket?.votes || []" size="small" border>
        <el-table-column label="投票人" min-width="130">
          <template #default="{ row }">{{ row.voterRealName || row.voterUsername || '-' }}</template>
        </el-table-column>
        <el-table-column label="决定" width="100">
          <template #default="{ row }">
            <el-tag :type="row.decision === 'AGREE' ? 'success' : 'danger'" size="small">
              {{ row.decision === 'AGREE' ? '同意' : '驳回' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="意见" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.comment || '-' }}</template>
        </el-table-column>
        <el-table-column label="投票时间" width="170" prop="votedAt"/>
      </el-table>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="voteVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 公司变更单详情（流程图 + 审批轨迹 + 催办） -->
    <el-dialog v-model="opDetailVisible" :close-on-click-modal="false" title="公司变更单详情" width="900px">
      <el-alert v-if="opDetail && opDetail.status === 'PENDING'" :closable="false" :title="opNextApproverTitle" show-icon
                style="margin-bottom: 12px" type="warning"/>
      <WorkflowDiagram :diagram="opDiagram" style="margin-bottom: 16px"/>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="变更单号">{{ opDetail?.opNo }}</el-descriptions-item>
        <el-descriptions-item label="变更类型">{{ opDetail ? opTypeText(opDetail.opType) : '' }}</el-descriptions-item>
        <el-descriptions-item label="目标公司">{{
            (opDetail?.companyUscc || '-') + (opDetail?.companyName ? ' ' + opDetail.companyName : '')
          }}
        </el-descriptions-item>
        <el-descriptions-item label="变更事由" :span="2">{{ opDetail?.reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ opDetail ? statusText(opDetail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="发起人/时间">{{ opDetail?.requestedByName || '-' }}
          / {{ opDetail?.requestedAt }}
        </el-descriptions-item>
        <template v-if="opDetail && opDetail.opType === 'REASSIGN_HOLDERS'">
          <el-descriptions-item label="重新指派方式" :span="2">原 5 名密钥保管人投票（同意 &gt;3 即执行，不经过公司审批员）</el-descriptions-item>
        </template>
        <template v-else>
          <el-descriptions-item label="被指派审批人">{{
              opDetail?.assigneeRealName || '未指派（按角色流转）'
            }}
          </el-descriptions-item>
        </template>
        <el-descriptions-item label="审批人/时间">{{ opDetail?.reviewedByName || '-' }}
          / {{ opDetail?.reviewedAt || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="审批意见" :span="2">{{ opDetail?.reviewComment || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="trail-title">审批轨迹</div>
      <el-timeline v-if="opTrail.length" class="trail">
        <el-timeline-item v-for="t in opTrail" :key="t.id" :hollow="t.step === 'URGE'" :timestamp="t.createdAt"
                          :type="trailType(t.step)">
          <div class="trail-step">{{ trailStepText(t.step) }}</div>
          <div class="trail-who">{{ t.operatorRealName || '无' }}<span
              v-if="t.operatorRole">（{{ t.operatorRole }}）</span></div>
          <div v-if="t.comment" class="trail-comment">{{ t.comment }}</div>
        </el-timeline-item>
      </el-timeline>
      <template #footer>
        <div class="dialog-footer">
          <el-button v-if="opCanUrge(opDetail)" :loading="opUrging" type="warning" @click="onUrge(opDetail)">催办审批人
          </el-button>
          <el-button @click="opDetailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {Refresh, Search} from '@element-plus/icons-vue'
import {approveCompanyOp, cancelCompanyOp, detailCompanyOp, listCompanyOpTrail, pageCompanyOps, rejectCompanyOp, takeCompanyOpReceipt, urgeCompanyOp, voteCompanyOp} from '@/api/system'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'
import {hasPerm} from '@/lib/perm'
import {copyText} from '@/lib/clipboard'
import {getWorkflowDiagram} from '@/api/workflow'
import WorkflowDiagram from '@/views/WorkflowDiagram.vue'

const route = useRoute()
const router = useRouter()

// CREATE 仅历史单可查（新建公司已改为直接执行，不再走审批）；
// REASSIGN_HOLDERS 重新指派保管人：发起=公司管理员，执行条件=原 5 名保管人投票 >3 同意，不经过公司审批员
const OP_TYPE_LABELS = {
  CREATE: '新建公司',
  SUSPEND: '暂停公司',
  RESUME: '恢复公司',
  CLOSE: '关闭公司',
  RESET_CREDENTIAL: '重置接入密钥',
  REASSIGN_HOLDERS: '重新指派保管人'
}

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
    RESET_CREDENTIAL: 'warning',
    REASSIGN_HOLDERS: 'primary'
  }[t] || 'info'
}

function statusText(s) {
  return {PENDING: '待审批', EXECUTED: '已执行', REJECTED: '已驳回', CANCELLED: '已撤销'}[s] || s || '-'
}

function statusTag(s) {
  return {PENDING: 'warning', EXECUTED: 'success', REJECTED: 'danger', CANCELLED: 'info'}[s] || 'info'
}

// 操作列：全部平铺，不再收进「更多」下拉。
// 重新指派保管人单由原保管人投票（同意/驳回，myVotable 由服务端按当前登录人回算），不走公司审批员通道；
// 其他类型维持审批员审批；撤回/领取回执沿用服务端逐行回算
function rowActions(row) {
  const acts = []
  acts.push({label: '详情', run: () => onDetail(row)})
  if (row.opType === 'REASSIGN_HOLDERS') {
    if (row.status === 'PENDING' && row.myVotable) {
      acts.push({label: '同意', type: 'success', run: () => onVote(row, true)})
      acts.push({label: '驳回', type: 'danger', run: () => onVote(row, false)})
    }
    acts.push({label: '投票明细', type: 'primary', run: () => onVoteDetail(row)})
  } else if (row.status === 'PENDING' && row.reviewable) {
    // reviewable 由后端按「本单被指派人 == 当前登录人」现算，与 approve/reject 接口同一口径；不可审的直接不给按钮
    acts.push({label: '审批通过', type: 'success', run: () => onApprove(row)})
    acts.push({label: '驳回', type: 'danger', run: () => onReject(row)})
  }
  if (row.status === 'PENDING' && row.urgeable && hasPerm('company:op:apply')) {
    acts.push({label: '催办', type: 'warning', run: () => onUrge(row)})
  }
  if (row.revocable) {
    acts.push({label: '撤回', type: 'info', run: () => onCancel(row)})
  }
  if (row.receiptClaimable) {
    acts.push({label: '领取接入凭证', type: 'primary', run: () => onTakeReceipt(row)})
  }
  return acts
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

// ===== 保管人投票（重新指派保管人单：原 5 名密钥保管人每人一票，>3 同意执行 / 2 驳回否决） =====
const voteVisible = ref(false)
const voteTicket = ref(null)

function onVoteDetail(row) {
  voteTicket.value = row
  voteVisible.value = true
}

// ===== 变更单详情（流程图 + 审批轨迹 + 催办） =====
const opDetailVisible = ref(false)
const opDetail = ref(null)
const opDiagram = ref(null)
const opTrail = ref([])
const opUrging = ref(false)
const opNextApproverTitle = computed(() => {
  const d = opDetail.value
  if (!d) return ''
  if (d.status === 'PENDING') {
    if (d.opType === 'REASSIGN_HOLDERS') {
      return '当前环节：等待原 5 名密钥保管人投票（同意 >3 即执行）'
    }
    return d.assigneeRealName
        ? `当前环节：等待【${d.assigneeRealName}】审批`
        : '当前环节：等待【公司审批员】审批（未指定具体人）'
  }
  if (d.status === 'EXECUTED') return '当前环节：已批准并执行'
  if (d.status === 'REJECTED') return '当前环节：已驳回'
  if (d.status === 'CANCELLED') return '当前环节：已撤销'
  return ''
})

async function onDetail(row) {
  opDetail.value = row
  opDetailVisible.value = true
  opTrail.value = []
  opDiagram.value = null
  try {
    listCompanyOpTrail(row.id).then(res => {
      opTrail.value = res.data || []
    })
    getWorkflowDiagram('COMPANY_OP', row.status, row.assigneeRealName).then(res => {
      opDiagram.value = res.data || null
    })
  } catch (e) {
  }
}

async function onUrge(row) {
  if (!row) return
  const target = row.opType === 'REASSIGN_HOLDERS' ? '原 5 名密钥保管人' : (row.assigneeRealName ? '审批人 ' + row.assigneeRealName : '公司审批员')
  await ElMessageBox.confirm(`向${target}发送催办提醒？`, '催办', {type: 'warning'})
  opUrging.value = true
  try {
    await urgeCompanyOp(row.id)
    ElMessage.success('已发送催办提醒')
    const res = await listCompanyOpTrail(row.id)
    opTrail.value = res.data || []
  } finally {
    opUrging.value = false
  }
}

function opCanUrge(row) {
  // 可否催办由服务端逐行回算（待审批且已指派审批人；重新指派保管人单回落通知原保管人）；审批人登录账号不再随出参下发
  return !!row && !!row.urgeable && hasPerm('company:op:apply')
}

function trailStepText(step) {
  return {
    SUBMIT: '提交变更',
    ASSIGN: '指定审批人',
    APPROVE: '审批执行',
    REJECT: '审批驳回',
    CANCEL: '撤销变更',
    VOTE: '保管人投票',
    EXECUTE: '执行重绑',
    URGE: '催办'
  }[step] || step
}

function trailType(step) {
  return {
    SUBMIT: 'primary',
    ASSIGN: 'info',
    APPROVE: 'success',
    REJECT: 'danger',
    EXECUTE: 'success',
    CANCEL: 'info',
    VOTE: 'warning',
    URGE: 'warning'
  }[step] || ''
}

async function onVote(row, agree) {
  if (agree) {
    const {value} = await ElMessageBox.prompt(
        `同意重新指派保管人（变更单 ${row.opNo}，目标 ${row.companyUscc}）？累计同意数达到 ${row.voteRequired ?? 4} 将自动执行重绑。投票意见可留空。`,
        '投票：同意',
        {type: 'success', confirmButtonText: '同意', cancelButtonText: '取消', inputPlaceholder: '投票意见（选填）'}
    )
    await voteCompanyOp(row.id, {agree: true, comment: String(value || '').trim() || undefined})
  } else {
    const {value} = await ElMessageBox.prompt(
        `驳回重新指派保管人（变更单 ${row.opNo}）？累计驳回达到 2 票即整单否决。驳回意见必填。`,
        '投票：驳回',
        {
          type: 'error',
          confirmButtonText: '驳回',
          cancelButtonText: '取消',
          inputPlaceholder: '驳回原因（必填）',
          inputValidator: v => (String(v || '').trim() ? true : '驳回必须填写投票意见')
        }
    )
    await voteCompanyOp(row.id, {agree: false, comment: String(value).trim()})
  }
  ElMessage.success('投票已记录')
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

onMounted(async () => {
  await load()
  await handleDeepLink()
})

/**
 * 深链直达（我的待办聚合页跳转）：?opId=xx 打开变更单详情。
 * 打开后清掉 query，避免刷新时重复弹框。
 */
async function handleDeepLink() {
  const opId = route.query.opId
  if (!opId) return
  try {
    const res = await detailCompanyOp(opId)
    await onDetail(res.data)
  } catch (e) {
    // 单据不存在或无权查看时留在列表
  }
  router.replace({path: '/company-ops'})
}
</script>

<style scoped>
.cred-desc {
  margin-top: 12px;
}
</style>
