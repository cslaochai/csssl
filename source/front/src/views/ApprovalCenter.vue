<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="公司">
            <el-select v-model="query.companyUscc" clearable placeholder="全部" style="width: 300px"
                       @change="handleSearch">
              <el-option
                  v-for="o in companyOptions"
                  :key="o.uscc"
                  :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                  :value="o.uscc"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="审批状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
              <el-option v-for="(label, code) in STATUS_LABELS" :key="code" :label="label" :value="code"/>
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
        <div class="query-actions">
          <el-button v-if="canApply" :icon="Plus" type="primary" @click="openApply">申请重建二级CA</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column class-name="cell-mono" label="单号" min-width="180" prop="opNo" show-overflow-tooltip/>
        <el-table-column label="公司" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.companyName || row.companyUscc }}
            <div class="cell-sub cell-mono">{{ row.companyUscc }}</div>
          </template>
        </el-table-column>
        <el-table-column label="重建事由" min-width="180" prop="reason" show-overflow-tooltip/>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审批进度" width="140">
          <template #default="{ row }">
            {{ row.approvedCount }}/{{ row.threshold }} 同意
            <div v-if="row.rejectedCount" class="cell-sub">{{ row.rejectedCount }} 驳回</div>
          </template>
        </el-table-column>
        <el-table-column label="份额收集" width="120">
          <template #default="{ row }">
            <span :class="row.collectedShareCount >= row.threshold ? 'share-ok' : ''">
              {{ row.collectedShareCount }}/{{ row.threshold }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="发起人" min-width="140">
          <template #default="{ row }">
            {{ row.requestedByName || '无' }}
            <div class="cell-sub">{{ row.requestedAt }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="220">
          <template #default="{ row }">
            <el-button
                v-for="a in visibleRowActions(row)"
                :key="a.label"
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
                  <el-dropdown-item v-for="a in moreRowActions(row)" :key="a.label" :command="a">{{ a.label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!loading && !rows.length" class="empty-tip">暂无匹配的申请单</div>
      <div ref="footerRef" class="list-footer">
        <el-pagination
            v-model:current-page="query.pageNo"
            v-model:page-size="query.pageSize"
            :page-sizes="PAGE_SIZES"
            :total="total"
            layout="total, sizes, prev, pager, next, jumper"
            @current-change="handleCurrentChange"
            @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 审批弹框：同意须提交本人持有的根私钥份额 -->
    <el-dialog v-model="approveVisible" :close-on-click-modal="false" title="审批二级CA重建申请" width="720px">
      <template v-if="approveTicket">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">
            <span class="cell-mono">{{ approveTicket.opNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="公司">
            {{ approveTicket.companyName || approveTicket.companyUscc }}（{{ approveTicket.companyUscc }}）
          </el-descriptions-item>
          <el-descriptions-item label="重建事由" :span="2">{{ approveTicket.reason }}</el-descriptions-item>
          <el-descriptions-item label="发起人">
            {{ approveTicket.requestedByName }} · {{ approveTicket.requestedAt }}
          </el-descriptions-item>
          <el-descriptions-item label="审批进度" :span="2">
            {{ approveTicket.approvedCount }}/{{ approveTicket.threshold }} 同意
            <template v-if="approveTicket.rejectedCount">，{{ approveTicket.rejectedCount }} 驳回</template>
            · 已收集份额 {{ approveTicket.collectedShareCount }}/{{ approveTicket.threshold }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
            :closable="false"
            class="dialog-tip"
            show-icon
            title="同意即代表你以密钥保管人身份放行本次重建，并把你持有的那份根私钥份额提交给本次申请单；3 名保管人各提交一份后即可直接解封重建。"
            type="warning"
        />
        <el-input
            v-model="approveComment"
            :rows="2"
            class="dialog-tip"
            maxlength="200"
            placeholder="审批意见（同意可留空，驳回必填）"
            show-word-limit
            type="textarea"
        />
        <template v-if="myShare">
          <div class="share-label">我的密钥份额（第 {{ myShare.index }}/{{ myShare.total }} 份，已自动填入）</div>
          <el-input :model-value="myShare.shareJson" :rows="4" readonly type="textarea"/>
        </template>
        <el-alert
            v-else
            :closable="false"
            class="dialog-tip"
            show-icon
            title="未找到你本人在该公司持有的根私钥份额，无法同意（仅可驳回）。请确认你已被指定为该公司密钥保管人。"
            type="error"
        />
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="deciding" @click="approveVisible = false">取消</el-button>
          <el-button :disabled="deciding" type="danger" @click="onReject">驳回</el-button>
          <el-button v-if="myShare" :loading="deciding" type="primary" @click="onApprove">同意并提交份额</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 执行弹框：优先使用审批阶段已收集的份额 -->
    <el-dialog v-model="executeVisible" :close-on-click-modal="false" title="提交份额解封并重建二级CA" width="680px">
      <template v-if="executeTicket">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">
            <span class="cell-mono">{{ executeTicket.opNo }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="公司">
            {{ executeTicket.companyName || executeTicket.companyUscc }}（{{ executeTicket.companyUscc }}）
          </el-descriptions-item>
          <el-descriptions-item label="审批结果" :span="2">
            {{ executeTicket.approvedCount }}/{{ executeTicket.threshold }} 名保管人同意
          </el-descriptions-item>
        </el-descriptions>
        <template v-if="canUseCollected">
          <el-alert
              :closable="false"
              class="dialog-tip"
              show-icon
              :title="`审批阶段已收集 ${executeTicket.collectedShareCount} 份保管人份额，达到门限，可直接解封根私钥并重建。`"
              type="success"
          />
          <div class="dialog-tip share-label">
            执行将用根私钥（仅内存加载）重新签发二级CA（SUB_CA），已签发的二级CA链需重新下发，操作不可撤回。
          </div>
        </template>
        <template v-else>
          <el-alert
              :closable="false"
              class="dialog-tip"
              show-icon
              :title="`审批阶段仅收集到 ${executeTicket.collectedShareCount}/${executeTicket.threshold} 份份额，请手动粘贴 ≥${executeTicket.threshold} 份根私钥份额。`"
              type="warning"
          />
          <el-input
              v-for="i in executeThreshold"
              :key="i"
              v-model="executeShares[i - 1]"
              :placeholder="`份额 JSON（第 ${i} 份，从「我的密钥份额」或保管人处获取）`"
              :rows="3"
              class="share-input"
              type="textarea"
          />
        </template>
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="executing" @click="executeVisible = false">取消</el-button>
          <el-button v-if="canUseCollected" :loading="executing" type="warning" @click="onExecuteCollected">
            解封根私钥并重建二级CA
          </el-button>
          <el-button v-else :disabled="pastedCount < executeThreshold" :loading="executing" type="warning"
                     @click="onExecuteManual">
            加载根私钥并重建
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 详情弹框 -->
    <el-dialog v-model="detailVisible" :title="`申请单详情：${detail?.opNo || ''}`" width="900px">
      <template v-if="detail">
        <WorkflowDiagram :diagram="diagram" style="margin-bottom: 16px"/>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="公司">
            {{ detail.companyName || detail.companyUscc }}（{{ detail.companyUscc }}）
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="重建事由" :span="2">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item label="发起人">
            {{ detail.requestedByName }} · {{ detail.requestedAt }}
          </el-descriptions-item>
          <el-descriptions-item label="审批进度" :span="2">
            {{ detail.approvedCount }}/{{ detail.threshold }} 同意
            <template v-if="detail.rejectedCount">，{{ detail.rejectedCount }} 驳回</template>
            · 已收集份额 {{ detail.collectedShareCount }}/{{ detail.threshold }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.executedAt" label="执行时间">{{ detail.executedAt }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">密钥保管人（5 名）</el-divider>
        <el-table :data="detail.holders || []" size="small">
          <el-table-column label="份额编号" prop="shareIndex" width="100"/>
          <el-table-column label="姓名" min-width="120" prop="holderRealName"/>
        </el-table>
        <el-divider content-position="left">审批记录</el-divider>
        <el-table :data="detail.approvals || []" size="small">
          <el-table-column label="审批人" min-width="120" prop="approverRealName"/>
          <el-table-column label="决定" width="90">
            <template #default="{ row }">
              <el-tag :type="row.decision === 'APPROVED' ? 'success' : 'danger'" size="small">
                {{ row.decision === 'APPROVED' ? '同意' : '驳回' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="份额" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.decision === 'APPROVED'" size="small" type="info">已提交</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="意见" min-width="140" prop="comment" show-overflow-tooltip/>
          <el-table-column label="时间" prop="decidedAt" width="160"/>
        </el-table>
        <el-divider content-position="left">审批轨迹</el-divider>
        <el-timeline v-if="trail.length" class="trail">
          <el-timeline-item
              v-for="t in trail"
              :key="t.id"
              :timestamp="t.createdAt"
              placement="top"
          >
            <div class="trail-step">{{ trailStepText(t.step) }}</div>
            <div class="trail-who">{{ t.operatorRealName || '系统' }}</div>
            <div v-if="t.comment" class="trail-comment">{{ t.comment }}</div>
          </el-timeline-item>
        </el-timeline>
        <div v-else class="empty-tip">暂无轨迹记录</div>
      </template>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="detailVisible = false">关闭</el-button>
          <el-button v-if="canUrge" :loading="urging" type="warning" @click="onUrge">催办保管人</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 申请重建弹框 -->
    <el-dialog v-model="applyVisible" :close-on-click-modal="false" title="申请重建二级CA" width="560px">
      <el-alert
          :closable="false"
          class="dialog-tip"
          show-icon
          title="重建将用根私钥重新签发二级CA（SUB_CA），需 5 名密钥保管人中 3 人同意；同意时各保管人须提交本人份额。"
          type="warning"
      />
      <el-form class="dialog-tip" label-width="80px">
        <el-form-item label="公司" required>
          <el-select v-model="applyForm.companyUscc" placeholder="选择公司" style="width: 100%">
            <el-option
                v-for="o in companyOptions"
                :key="o.uscc"
                :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                :value="o.uscc"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="重建事由" required>
          <el-input
              v-model="applyForm.reason"
              :rows="3"
              maxlength="255"
              placeholder="业务依据（审批人据此核对），必填"
              show-word-limit
              type="textarea"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="applying" @click="applyVisible = false">取消</el-button>
          <el-button :loading="applying" type="primary" @click="onApply">提交申请</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {ArrowDown, Plus, Refresh, Search} from '@element-plus/icons-vue'
import {
  applySubCaRebuild,
  approveSubCaRebuild,
  cancelSubCaRebuild,
  detailSubCaRebuild,
  executeSubCaRebuild,
  getShareOverview,
  listCompaniesCached,
  listSubCaRebuildTrail,
  pageSubCaRebuild,
  rejectSubCaRebuild,
  urgeSubCaRebuild
} from '@/api/system'
import {getWorkflowDiagram} from '@/api/workflow'
import WorkflowDiagram from '@/views/WorkflowDiagram.vue'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'
import {hasPerm} from '@/lib/perm'

const route = useRoute()
const router = useRouter()

const STATUS_LABELS = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CANCELLED: '已撤回',
  EXECUTED: '已执行'
}
const STATUS_TAG = {
  PENDING: 'warning',
  APPROVED: 'primary',
  REJECTED: 'danger',
  CANCELLED: 'info',
  EXECUTED: 'success'
}

const canApply = computed(() => hasPerm('ca:subca:rebuild:apply'))
const canExecute = computed(() => hasPerm('ca:subca:rebuild:execute'))

const companyOptions = ref([])

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, companyUscc: '', status: '', keyword: ''})
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

function statusText(s) {
  return STATUS_LABELS[s] || s || '-'
}

function statusTag(s) {
  return STATUS_TAG[s] || 'info'
}

// ===== 操作列：审批（保管人）、执行重建（已批准）、撤回（发起人本人）、详情 =====
const ACTION_VISIBLE = 3

function rowActions(row) {
  const acts = []
  if (row.myPendingDecision) {
    acts.push({label: '审批', type: 'primary', run: () => openApprove(row)})
  }
  if (row.status === 'APPROVED' && canExecute.value) {
    acts.push({label: '执行重建', type: 'warning', run: () => openExecute(row)})
  }
  if (row.revocable) {
    acts.push({label: '撤回', type: 'info', run: () => onCancel(row)})
  }
  acts.push({label: '详情', type: 'primary', run: () => openDetail(row.id)})
  return acts
}

function visibleRowActions(row) {
  return rowActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return rowActions(row).slice(ACTION_VISIBLE)
}

async function load() {
  loading.value = true
  try {
    const res = await pageSubCaRebuild({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      companyUscc: query.companyUscc || undefined,
      status: query.status || undefined,
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
  query.companyUscc = ''
  query.status = ''
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

// ===== 审批弹框（同意须提交本人份额） =====
const approveVisible = ref(false)
const approveTicket = ref(null)
const approveComment = ref('')
const myShare = ref(null)
const deciding = ref(false)

async function openApprove(row) {
  const res = await detailSubCaRebuild(row.id)
  approveTicket.value = res.data
  approveComment.value = ''
  myShare.value = null
  approveVisible.value = true
  try {
    const overview = await getShareOverview(row.companyUscc)
    myShare.value = (overview.data || {}).mine || null
  } catch (e) {
    myShare.value = null
  }
}

async function onApprove() {
  deciding.value = true
  try {
    await approveSubCaRebuild(approveTicket.value.id, {
      comment: approveComment.value.trim() || null,
      shareJson: myShare.value.shareJson
    })
    ElMessage.success('已同意并提交你的份额')
    approveVisible.value = false
    await afterDecision(approveTicket.value)
  } finally {
    deciding.value = false
  }
}

async function onReject() {
  const comment = approveComment.value.trim()
  if (!comment) {
    ElMessage.warning('驳回必须填写审批意见')
    return
  }
  deciding.value = true
  try {
    await rejectSubCaRebuild(approveTicket.value.id, {comment})
    ElMessage.success('已驳回')
    approveVisible.value = false
    await afterDecision(approveTicket.value)
  } finally {
    deciding.value = false
  }
}

/** 审批后刷新；本次同意刚好凑齐门限时，提示发起人可执行重建 */
async function afterDecision(ticket) {
  await load()
  const res = await detailSubCaRebuild(ticket.id)
  const latest = res.data
  if (latest.executableByMe && ticket.status === 'PENDING' && canExecute.value) {
    openExecute(latest)
  }
}

// ===== 执行弹框 =====
const executeVisible = ref(false)
const executeTicket = ref(null)
const executeShares = ref([])
const executing = ref(false)

const executeThreshold = computed(() => executeTicket.value?.threshold || 3)
const canUseCollected = computed(() => !!executeTicket.value
    && executeTicket.value.collectedShareCount >= executeTicket.value.threshold)
const pastedCount = computed(() => executeShares.value.filter(s => s && s.trim()).length)

function openExecute(row) {
  executeTicket.value = row
  executeShares.value = Array.from({length: executeThreshold.value}, () => '')
  executeVisible.value = true
}

async function onExecuteCollected() {
  const confirmed = await ElMessageBox.confirm(
      `将用已收集的 ${executeTicket.value.collectedShareCount} 份份额解封根私钥（仅存内存），并用根重新签发二级CA。确定执行吗？`,
      '解封并重建二级CA',
      {type: 'warning', confirmButtonText: '确定执行', cancelButtonText: '取消'}
  ).catch(() => false)
  if (!confirmed) return
  executing.value = true
  try {
    await executeSubCaRebuild(executeTicket.value.id, {useCollectedShares: true})
    ElMessage.success('已执行二级CA重建（用根重新签发）')
    executeVisible.value = false
    await load()
  } finally {
    executing.value = false
  }
}

async function onExecuteManual() {
  const shares = executeShares.value.map(s => s.trim()).filter(Boolean)
  if (shares.length < executeThreshold.value) {
    ElMessage.warning(`至少需要粘贴 ${executeThreshold.value} 份份额`)
    return
  }
  executing.value = true
  try {
    await executeSubCaRebuild(executeTicket.value.id, {shareJsons: shares})
    ElMessage.success('已执行二级CA重建（用根重新签发）')
    executeVisible.value = false
    await load()
  } finally {
    executing.value = false
  }
}

// ===== 详情弹框 =====
const detailVisible = ref(false)
const detail = ref(null)
const diagram = ref(null)
const trail = ref([])
const urging = ref(false)

// 催办：仅发起人本人对「待审批」单可催办（3-of-5 多人投票，按未投票的保管人逐人通知）
const canUrge = computed(() => !!detail.value
    && detail.value.status === 'PENDING'
    && detail.value.revocable)

async function openDetail(id) {
  try {
    const res = await detailSubCaRebuild(id)
    detail.value = res.data
    diagram.value = null
    trail.value = []
    detailVisible.value = true
    getWorkflowDiagram('SUB_CA_REBUILD', res.data.status, null).then(r => {
      diagram.value = r.data || null
    })
    listSubCaRebuildTrail(id).then(r => {
      trail.value = r.data || []
    })
  } catch (e) {
    detail.value = null
  }
}

async function onUrge() {
  if (!detail.value) return
  const confirmed = await ElMessageBox.confirm(
      `向尚未投票的密钥保管人发送催办提醒？申请单 ${detail.value.opNo}`,
      '催办',
      {type: 'warning', confirmButtonText: '发送催办', cancelButtonText: '取消'}
  ).catch(() => false)
  if (!confirmed) return
  urging.value = true
  try {
    await urgeSubCaRebuild(detail.value.id)
    ElMessage.success('已发送催办提醒')
    const res = await listSubCaRebuildTrail(detail.value.id)
    trail.value = res.data || []
  } finally {
    urging.value = false
  }
}

/** 轨迹步骤文案 */
function trailStepText(step) {
  return {
    SUBMIT: '发起申请',
    VOTE: '保管人投票',
    EXECUTE: '执行重建',
    CANCEL: '撤回申请',
    URGE: '催办'
  }[step] || step
}

// ===== 撤回 =====
async function onCancel(row) {
  const confirmed = await ElMessageBox.confirm(
      `撤回二级CA重建申请（${row.opNo}）？撤回后需重新发起。`,
      '撤回申请',
      {type: 'warning', confirmButtonText: '确定撤回', cancelButtonText: '取消'}
  ).catch(() => false)
  if (!confirmed) return
  await cancelSubCaRebuild(row.id)
  ElMessage.success('已撤回')
  await load()
}

// ===== 申请重建 =====
const applyVisible = ref(false)
const applying = ref(false)
const applyForm = reactive({companyUscc: '', reason: ''})

function openApply() {
  applyForm.companyUscc = query.companyUscc || defaultCompany()
  applyForm.reason = ''
  applyVisible.value = true
}

function defaultCompany() {
  return companyOptions.value.find(o => o.status === 'ACTIVE')?.uscc || companyOptions.value[0]?.uscc || ''
}

async function onApply() {
  if (!applyForm.companyUscc) {
    ElMessage.warning('请选择目标公司')
    return
  }
  const reason = applyForm.reason.trim()
  if (!reason) {
    ElMessage.warning('请填写重建事由')
    return
  }
  applying.value = true
  try {
    await applySubCaRebuild({companyUscc: applyForm.companyUscc, reason})
    ElMessage.success('已发起二级CA重建申请，已通知 5 名密钥保管人审批')
    applyVisible.value = false
    await load()
  } finally {
    applying.value = false
  }
}

onMounted(async () => {
  companyOptions.value = await listCompaniesCached()
  await load()
  // 消息待办直达：?ticketId=xx 打开审批弹框，?ticketId=xx&action=execute 打开执行弹框
  const ticketId = route.query.ticketId
  const wantedAction = route.query.action
  if (!ticketId) return
  router.replace({path: '/approval-center'})
  try {
    const res = await detailSubCaRebuild(ticketId)
    const ticket = res.data
    if (wantedAction === 'execute') {
      openExecute(ticket)
    } else if (ticket.myPendingDecision) {
      openApprove(ticket)
    } else {
      openDetail(ticket.id)
    }
  } catch (e) {
    // 单据不存在或无权查看时留在列表
  }
})
</script>

<style scoped>
.share-ok {
  color: #67c23a;
  font-weight: 600;
}

.share-label {
  font-size: 13px;
  color: #606266;
  line-height: 1.6;
}

.share-input {
  margin-bottom: 8px;
}

.dialog-tip {
  margin-top: 12px;
}

.trail :deep(.el-timeline-item__timestamp) {
  color: #909399;
  font-size: 12px;
}

.trail-step {
  font-weight: 600;
  color: #303133;
}

.trail-who {
  font-size: 13px;
  color: #606266;
  margin-top: 2px;
}

.trail-comment {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}

.empty-tip {
  color: #909399;
  font-size: 13px;
  padding: 8px 0;
  text-align: center;
}
</style>
