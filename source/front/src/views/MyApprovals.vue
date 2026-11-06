<template>
  <div class="todo-page">
    <!-- 概览卡：三组待办的数量 -->
    <el-card class="summary-card" shadow="never">
      <div class="summary-row">
        <div class="summary-item" :class="{ active: tab === 'pending' }" @click="tab = 'pending'">
          <div class="summary-num">{{ pending.length }}</div>
          <div class="summary-label">待我处理</div>
        </div>
        <div class="summary-item" :class="{ active: tab === 'initiated' }" @click="tab = 'initiated'">
          <div class="summary-num">{{ initiated.length }}</div>
          <div class="summary-label">我发起的</div>
        </div>
        <div class="summary-item" :class="{ active: tab === 'done' }" @click="tab = 'done'">
          <div class="summary-num">{{ done.length }}</div>
          <div class="summary-label">已办</div>
        </div>
      </div>
      <div class="summary-tip">
        四条审批流（RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建）统一在这里处理：
        批准、驳回、签发、投票贴份额、执行重建、领取回执，全部就地完成，不再跳转到各业务台账。
      </div>
    </el-card>

    <!-- 待办列表 -->
    <el-card class="table-card" shadow="never">
      <el-tabs v-model="tab" class="todo-tabs">
        <el-tab-pane name="pending">
          <template #label>
            <span class="tab-label">待我处理<el-badge v-if="pending.length" :value="pending.length" class="tab-badge"/></span>
          </template>
          <el-table v-loading="loading" :data="pending" stripe style="width: 100%">
            <el-table-column label="流程" min-width="180">
              <template #default="{ row }">
                <el-tag size="small" :type="bizTag(row.bizType)">{{ bizLabel(row.bizType) }}</el-tag>
                <div class="cell-sub cell-mono">{{ row.bizNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="220" prop="title" show-overflow-tooltip/>
            <el-table-column label="当前环节" min-width="140" prop="stepName"/>
            <el-table-column label="当前处理人" min-width="140">
              <template #default="{ row }">{{ row.nextHandler || row.assigneeRealName || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="warning">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起" min-width="160">
              <template #default="{ row }">
                {{ row.requestedByName || '无' }}
                <div class="cell-sub">{{ row.requestedAt || '' }}</div>
              </template>
            </el-table-column>
            <el-table-column align="right" fixed="right" label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="openDetail(row)">{{ row.actionLabel || '处理' }}</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-tip">暂无待我处理的单据</div>
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="我发起的" name="initiated">
          <el-table v-loading="loading" :data="initiated" stripe style="width: 100%">
            <el-table-column label="流程" min-width="180">
              <template #default="{ row }">
                <el-tag size="small" :type="bizTag(row.bizType)">{{ bizLabel(row.bizType) }}</el-tag>
                <div class="cell-sub cell-mono">{{ row.bizNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="220" prop="title" show-overflow-tooltip/>
            <el-table-column label="当前环节" min-width="140" prop="stepName"/>
            <el-table-column label="当前处理人" min-width="140">
              <template #default="{ row }">{{ row.nextHandler || row.assigneeRealName || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="primary">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起时间" prop="requestedAt" width="170"/>
            <el-table-column align="right" fixed="right" label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="openDetail(row)">处理</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-tip">暂无我发起的单据</div>
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="已办" name="done">
          <el-table v-loading="loading" :data="done" stripe style="width: 100%">
            <el-table-column label="流程" min-width="180">
              <template #default="{ row }">
                <el-tag size="small" :type="bizTag(row.bizType)">{{ bizLabel(row.bizType) }}</el-tag>
                <div class="cell-sub cell-mono">{{ row.bizNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="220" prop="title" show-overflow-tooltip/>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="success">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起" min-width="160">
              <template #default="{ row }">
                {{ row.requestedByName || '无' }}
                <div class="cell-sub">{{ row.requestedAt || '' }}</div>
              </template>
            </el-table-column>
            <el-table-column align="right" fixed="right" label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="openDetail(row)">查看</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-tip">暂无已办单据</div>
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 处理弹框：按 bizType 渲染完整业务上下文 + 就地动作 -->
    <el-dialog
        v-model="drawerVisible"
        :title="drawerTitle"
        width="960px"
        top="5vh"
        :close-on-press-escape="true"
        destroy-on-close
    >
      <div v-loading="detailLoading" class="drawer-body">
        <template v-if="data">
          <!-- 当前环节提示 -->
          <el-alert
              v-if="data.row?.stepName"
              :closable="false"
              show-icon
              style="margin-bottom: 12px"
              :type="data.row.status === 'PENDING' ? 'warning' : 'info'"
              :title="`当前环节：${data.row.stepName}` + (data.row.assigneeRealName ? `（${data.row.assigneeRealName}）` : '')"
          />

          <!-- 流程图 -->
          <WorkflowDiagram v-if="diagram" :diagram="diagram" style="margin-bottom: 16px"/>

          <!-- 业务详情：按 bizType 渲染 -->
          <el-descriptions :column="2" border size="small" title="业务详情">
            <template v-if="data.bizType === 'CERT_APPLICATION'">
              <el-descriptions-item label="申请单号">
                <span class="cell-mono">{{ d('applyNo') }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="状态">{{ data.row.statusText }}</el-descriptions-item>
              <el-descriptions-item label="申请人类别">
                {{ d('applicantType') === 'INSTITUTION' ? '单位' : '个人' }}
              </el-descriptions-item>
              <el-descriptions-item label="申请人">{{ d('applicantName') }}</el-descriptions-item>
              <el-descriptions-item label="所属公司">
                {{ data.row.companyName || data.row.companyUscc || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="证书模板">
                {{ data.row.templateCode || ('#' + d('templateId')) }}
              </el-descriptions-item>
              <el-descriptions-item label="主体DN" :span="2">{{ d('subjectDn') }}</el-descriptions-item>
              <el-descriptions-item label="主体别名（SAN）" :span="2">{{ d('san') || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="d('publicKeyPem')" label="公钥PEM" :span="2">
                <pre class="pem-block">{{ d('publicKeyPem') }}</pre>
              </el-descriptions-item>
              <el-descriptions-item label="用途说明">{{ d('description') || '-' }}</el-descriptions-item>
              <el-descriptions-item label="来源渠道">
                {{ data.row.externalChannel === 'API' ? 'HIS/外部系统接入' : '管理端WEB录入' }}
              </el-descriptions-item>
              <el-descriptions-item label="签发证书">
                {{ d('issuedCertId') ? `#${d('issuedCertId')}` : '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="审批人/时间">
                {{ d('reviewedByName') || '-' }}<template v-if="d('reviewedAt')"> / {{ d('reviewedAt') }}</template>
              </el-descriptions-item>
              <el-descriptions-item v-if="d('reviewComment')" label="审批意见" :span="2">
                {{ d('reviewComment') }}
              </el-descriptions-item>
            </template>

            <template v-else-if="data.bizType === 'CERT_OP'">
              <el-descriptions-item label="操作单号">
                <span class="cell-mono">{{ d('opNo') }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="状态">{{ data.row.statusText }}</el-descriptions-item>
              <el-descriptions-item label="操作类型">{{ data.row.opTypeText }}</el-descriptions-item>
              <el-descriptions-item label="证书主体">{{ d('certSubjectCn') }}</el-descriptions-item>
              <el-descriptions-item label="序列号" :span="2">
                <span class="cell-mono">{{ d('certSerialHex') || '-' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="所属公司">
                {{ data.row.companyName || data.row.companyUscc || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="操作原因">{{ reasonText(d('reason')) }}</el-descriptions-item>
              <el-descriptions-item v-if="d('validityDays')" label="新有效期">{{ d('validityDays') }} 天</el-descriptions-item>
              <el-descriptions-item label="发起人/时间">
                {{ d('requestedByName') || '-' }}<template v-if="d('requestedAt')"> / {{ d('requestedAt') }}</template>
              </el-descriptions-item>
              <el-descriptions-item label="被指派审批人">{{ d('assigneeRealName') || '未指派' }}</el-descriptions-item>
              <el-descriptions-item label="审批人/时间">
                {{ d('reviewedByName') || '-' }}<template v-if="d('reviewedAt')"> / {{ d('reviewedAt') }}</template>
              </el-descriptions-item>
              <el-descriptions-item v-if="d('reviewComment')" label="审批意见" :span="2">{{ d('reviewComment') }}</el-descriptions-item>
            </template>

            <template v-else-if="data.bizType === 'COMPANY_OP'">
              <el-descriptions-item label="变更单号">
                <span class="cell-mono">{{ d('opNo') }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="状态">{{ data.row.statusText }}</el-descriptions-item>
              <el-descriptions-item label="变更类型">{{ data.row.opTypeText }}</el-descriptions-item>
              <el-descriptions-item label="目标公司">
                {{ (d('companyUscc') || '-') + (d('companyName') ? ' ' + d('companyName') : '') }}
              </el-descriptions-item>
              <el-descriptions-item label="变更事由" :span="2">{{ d('reason') || '-' }}</el-descriptions-item>
              <el-descriptions-item label="发起人/时间">
                {{ d('requestedByName') || '-' }}<template v-if="d('requestedAt')"> / {{ d('requestedAt') }}</template>
              </el-descriptions-item>
              <el-descriptions-item v-if="d('opType') !== 'REASSIGN_HOLDERS'" label="被指派审批人">
                {{ d('assigneeRealName') || '未指派' }}
              </el-descriptions-item>
              <el-descriptions-item v-else label="投票方式">原 5 名密钥保管人投票（同意 >3 即执行）</el-descriptions-item>
              <el-descriptions-item label="审批人/时间">
                {{ d('reviewedByName') || '-' }}<template v-if="d('reviewedAt')"> / {{ d('reviewedAt') }}</template>
              </el-descriptions-item>
              <el-descriptions-item v-if="d('reviewComment')" label="审批意见" :span="2">{{ d('reviewComment') }}</el-descriptions-item>
            </template>

            <template v-else-if="data.bizType === 'SUB_CA_REBUILD'">
              <el-descriptions-item label="重建单号">
                <span class="cell-mono">{{ d('opNo') }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="状态">{{ data.row.statusText }}</el-descriptions-item>
              <el-descriptions-item label="公司">
                {{ d('companyName') || d('companyUscc') }}（{{ d('companyUscc') }}）
              </el-descriptions-item>
              <el-descriptions-item label="发起人">
                {{ d('requestedByName') }} · {{ d('requestedAt') }}
              </el-descriptions-item>
              <el-descriptions-item label="重建事由" :span="2">{{ d('reason') }}</el-descriptions-item>
              <el-descriptions-item label="审批进度" :span="2">
                {{ d('approvedCount') }}/{{ d('threshold') }} 同意
                <template v-if="d('rejectedCount')">，{{ d('rejectedCount') }} 驳回</template>
                · 已收集份额 {{ d('collectedShareCount') }}/{{ d('threshold') }}
              </el-descriptions-item>
              <el-descriptions-item v-if="d('executedAt')" label="执行时间">{{ d('executedAt') }}</el-descriptions-item>
            </template>
          </el-descriptions>

          <!-- 保管人列表（SUB_CA） -->
          <template v-if="data.bizType === 'SUB_CA_REBUILD' && d('holders')?.length">
            <div class="block-title">密钥保管人（{{ d('holders').length }} 名）</div>
            <el-table :data="d('holders')" size="small" border>
              <el-table-column label="份额编号" prop="shareIndex" width="100"/>
              <el-table-column label="姓名" prop="holderRealName" min-width="120"/>
            </el-table>
          </template>

          <!-- 保管人投票明细（COMPANY_OP 重指派 / SUB_CA 审批记录） -->
          <template v-if="data.bizType === 'COMPANY_OP' && d('opType') === 'REASSIGN_HOLDERS' && d('votes')?.length">
            <div class="block-title">原保管人投票明细</div>
            <el-table :data="d('votes')" size="small" border>
              <el-table-column label="投票人" prop="voterRealName" min-width="120"/>
              <el-table-column label="决定" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.decision === 'AGREE' ? 'success' : 'danger'" size="small">
                    {{ row.decision === 'AGREE' ? '同意' : '驳回' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="意见" prop="comment" min-width="160" show-overflow-tooltip/>
              <el-table-column label="投票时间" prop="votedAt" width="160"/>
            </el-table>
          </template>

          <!-- 审批轨迹（业务留痕） -->
          <div v-if="trailRows.length" class="block-title">审批轨迹</div>
          <el-timeline v-if="trailRows.length" class="trail">
            <el-timeline-item
                v-for="t in trailRows"
                :key="t.id || (t.approverRealName + t.decidedAt)"
                :timestamp="t.createdAt || t.decidedAt"
                :type="trailType(t)"
            >
              <div class="trail-step">{{ trailStepText(t) }}</div>
              <div class="trail-who">
                {{ t.operatorRealName || t.approverRealName || '系统' }}
                <span v-if="t.operatorRole">（{{ t.operatorRole }}）</span>
                <span v-if="t.decision">（{{ t.decision === 'APPROVED' || t.decision === 'AGREE' ? '同意' : '驳回' }}）</span>
              </div>
              <div v-if="t.comment" class="trail-comment">{{ t.comment }}</div>
            </el-timeline-item>
          </el-timeline>

          <!-- 流程引擎历史（ Warm-Flow 节点轨迹 ） -->
          <div v-if="flowHistoryRows.length" class="block-title">流程节点历史</div>
          <el-table v-if="flowHistoryRows.length" :data="flowHistoryRows" size="small" border>
            <el-table-column label="节点" prop="nodeName" width="140"/>
            <el-table-column label="办理人" prop="handlerName" width="120"/>
            <el-table-column label="结果" width="90">
              <template #default="{ row }">
                <el-tag :type="row.resultText === '通过' ? 'success' : (row.resultText === '驳回' ? 'danger' : 'info')" size="small">
                  {{ row.resultText || row.result || '—' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="意见" prop="message" min-width="160" show-overflow-tooltip/>
            <el-table-column label="时间" prop="time" width="160"/>
          </el-table>

          <!-- 动作区：按服务端算好的 actions 渲染 -->
          <div v-if="actionList.length" class="action-zone">
            <div class="block-title">待办动作</div>

            <!-- 意见输入（APPROVE/REJECT/VOTE 共用） -->
            <el-input
                v-if="needComment"
                v-model="actionComment"
                :rows="2"
                placeholder="审批意见（驳回必填，同意可留空）"
                type="textarea"
                style="margin-bottom: 12px"
            />

            <!-- SUB_CA 执行重建：贴份额区 -->
            <template v-if="hasAction('EXECUTE_REBUILD')">
              <el-alert
                  v-if="canUseCollectedShares"
                  :closable="false"
                  show-icon
                  style="margin-bottom: 10px"
                  type="success"
                  :title="`审批阶段已收集 ${d('collectedShareCount')} 份份额，达到门限，可直接解封根私钥重建。`"
              />
              <template v-else>
                <el-alert
                    :closable="false"
                    show-icon
                    style="margin-bottom: 10px"
                    type="warning"
                    :title="`审批阶段仅收集到 ${d('collectedShareCount')}/${d('threshold')} 份份额，请手动粘贴 ≥${d('threshold')} 份根私钥份额。`"
                />
                <el-input
                    v-for="i in executeThreshold"
                    :key="i"
                    v-model="executeShares[i - 1]"
                    :rows="3"
                    :placeholder="`份额 JSON（第 ${i} 份）`"
                    style="margin-bottom: 8px"
                    type="textarea"
                />
              </template>
            </template>

            <div class="action-buttons">
              <el-button
                  v-if="hasAction('APPROVE')"
                  :loading="acting"
                  type="success"
                  @click="onApprove"
              >批准</el-button>
              <el-button
                  v-if="hasAction('REJECT')"
                  :loading="acting"
                  type="danger"
                  @click="onReject"
              >驳回</el-button>

              <template v-if="hasAction('VOTE')">
                <el-button
                    v-if="showSubCaVoteAgree"
                    :loading="acting"
                    type="success"
                    @click="onSubCaVoteAgree"
                >同意并提交份额</el-button>
                <el-button
                    v-else
                    :loading="acting"
                    type="success"
                    @click="onCompanyVote(true)"
                >同意</el-button>
                <el-button
                    :loading="acting"
                    type="danger"
                    @click="onVoteReject"
                >驳回</el-button>
              </template>

              <el-button
                  v-if="hasAction('ISSUE')"
                  :loading="acting"
                  type="warning"
                  @click="onIssue"
              >签发证书</el-button>

              <el-button
                  v-if="hasAction('EXECUTE_REBUILD')"
                  :loading="acting"
                  type="warning"
                  :disabled="!canUseCollectedShares && pastedShares < executeThreshold"
                  @click="onExecuteRebuild"
              >解封根私钥并重建二级CA</el-button>

              <el-button
                  v-if="hasAction('RECEIPT')"
                  :loading="acting"
                  type="primary"
                  @click="onTakeReceipt"
              >领取接入凭证</el-button>

              <el-button
                  v-if="hasAction('CANCEL')"
                  :loading="acting"
                  type="info"
                  @click="onCancel"
              >撤回申请</el-button>

              <el-button
                  v-if="hasAction('URGE')"
                  :loading="acting"
                  type="warning"
                  @click="onUrge"
              >催办</el-button>
            </div>
          </div>
        </template>
      </div>
    </el-dialog>

    <!-- 领取凭证回执展示（一次性） -->
    <el-dialog v-model="receiptVisible" :close-on-click-modal="false" title="接入凭证" width="620px">
      <el-alert
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="密钥仅展示一次，关闭后无法再次查看；请立即复制到 HIS 等外部系统的配置中。"
          type="warning"
      />
      <el-descriptions v-if="receipt" :column="2" border>
        <el-descriptions-item label="接入ID（App ID）" :span="2">
          <span class="cell-mono">{{ receipt.appId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="接入密钥" :span="2">
          <span class="cell-mono">{{ receipt.secret }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="绑定公司">{{ receipt.companyName || receipt.companyUscc }}</el-descriptions-item>
        <el-descriptions-item label="密钥版本">{{ receipt.secretVersion ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="receiptVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {getWorkflowDiagram, getWorkflowTodoBoard, getWorkflowTodoDetail} from '@/api/workflow'
import {
  approveApplication,
  approveCertOp,
  approveCompanyOp,
  approveSubCaRebuild,
  cancelApplication,
  cancelCertOp,
  cancelCompanyOp,
  cancelSubCaRebuild,
  executeSubCaRebuild,
  getShareOverview,
  issueApplication,
  rejectApplication,
  rejectCertOp,
  rejectCompanyOp,
  rejectSubCaRebuild,
  takeCompanyOpReceipt,
  urgeApplication,
  urgeCertOp,
  urgeCompanyOp,
  urgeSubCaRebuild,
  voteCompanyOp
} from '@/api/system'
import WorkflowDiagram from '@/views/WorkflowDiagram.vue'

const loading = ref(false)
const tab = ref('pending')
const pending = ref([])
const initiated = ref([])
const done = ref([])

const BIZ_LABELS = {
  CERT_APPLICATION: 'RA 申请',
  CERT_OP: '证书操作单',
  COMPANY_OP: '公司变更单',
  SUB_CA_REBUILD: 'SUB_CA 重建'
}
const BIZ_TAGS = {
  CERT_APPLICATION: 'primary',
  CERT_OP: 'warning',
  COMPANY_OP: 'success',
  SUB_CA_REBUILD: 'danger'
}

function bizLabel(t) { return BIZ_LABELS[t] || t }
function bizTag(t) { return BIZ_TAGS[t] || 'info' }

// ============ 抽屉 ============
const drawerVisible = ref(false)
const detailLoading = ref(false)
const data = ref(null)
const diagram = ref(null)
const acting = ref(false)
const actionComment = ref('')
const myShare = ref(null)
const executeShares = ref([])

const drawerTitle = computed(() => {
  if (!data.value) return '待办处理'
  return `${data.value.bizTypeName || '待办'} · ${data.value.row?.bizNo || ''}`
})

const row = computed(() => data.value?.row || {})
const detail = computed(() => data.value?.detail || {})
const actionList = computed(() => row.value?.actions || [])
const trailRows = computed(() => data.value?.trail || [])
const flowHistoryRows = computed(() => data.value?.flowHistory || [])

function d(key) {
  return detail.value?.[key]
}

function hasAction(a) {
  return actionList.value.includes(a)
}

// 审批/驳回/投票都需要意见框
const needComment = computed(() =>
    hasAction('APPROVE') || hasAction('REJECT') || hasAction('VOTE')
)

// SUB_CA 重建：同意按钮需要 shareJson（预取）
const showSubCaVoteAgree = computed(() =>
    data.value?.bizType === 'SUB_CA_REBUILD' && hasAction('VOTE')
)

const executeThreshold = computed(() => d('threshold') || 3)
const canUseCollected = computed(() =>
    !!d('collectedShareCount') && d('collectedShareCount') >= executeThreshold.value
)
const pastedShares = computed(() =>
    executeShares.value.filter(s => s && String(s).trim()).length
)

const REASON_MAP = {
  KEY_COMPROMISE: '密钥泄露',
  CA_COMPROMISE: 'CA泄露',
  AFFILIATION_CHANGED: '隶属关系变更',
  SUPERSEDED: '被新证书替代',
  CESSATION_OF_OPERATION: '停止运营',
  CERTIFICATE_HOLD: '证书挂起',
  PRIVILEGE_WITHDRAWN: '权限撤销',
  UNSPECIFIED: '未指定'
}
function reasonText(r) { return REASON_MAP[r] || r || '未指定' }

function trailStepText(t) {
  if (t.step) {
    return {
      SUBMIT: '提交', ASSIGN: '指派', APPROVE: '批准', REJECT: '驳回',
      ISSUE: '签发', CANCEL: '撤销', VOTE: '投票', EXECUTE: '执行', URGE: '催办'
    }[t.step] || t.step
  }
  if (t.approverRealName) return '保管人投票'
  return '轨迹'
}

function trailType(t) {
  const s = t.step || ''
  if (['APPROVE', 'ISSUE', 'EXECUTE'].includes(s)) return 'success'
  if (['REJECT'].includes(s)) return 'danger'
  if (['URGE', 'VOTE'].includes(s)) return 'warning'
  if (['SUBMIT', 'CANCEL', 'ASSIGN'].includes(s)) return 'primary'
  return 'info'
}

async function openDetail(rowItem) {
  drawerVisible.value = true
  detailLoading.value = true
  data.value = null
  diagram.value = null
  myShare.value = null
  actionComment.value = ''
  executeShares.value = []
  try {
    const res = await getWorkflowTodoDetail(rowItem.bizType, rowItem.bizId)
    data.value = res.data
    // 流程图
    try {
      const dg = await getWorkflowDiagram(rowItem.bizType, row.value.status, row.value.assigneeRealName)
      diagram.value = dg.data
    } catch (e) { /* 无流程定义的历史单忽略 */ }
    // SUB_CA 投票同意：预取本人份额
    if (rowItem.bizType === 'SUB_CA_REBUILD' && hasAction('VOTE') && row.value.companyUscc) {
      try {
        const ov = await getShareOverview(row.value.companyUscc)
        myShare.value = ov.data?.mine || null
      } catch (e) { myShare.value = null }
    }
    // 执行重建：初始化份额输入
    if (hasAction('EXECUTE_REBUILD')) {
      executeShares.value = Array.from({length: executeThreshold.value}, () => '')
    }
  } catch (e) {
    // 错误已由拦截器提示
  } finally {
    detailLoading.value = false
  }
}

/** 操作成功后：刷新看板列表并关闭弹窗（动作已完成，留在弹窗里没有意义） */
async function afterAction() {
  await load()
  drawerVisible.value = false
}

// ============ 各动作 ============
function requireCommentOnReject(action) {
  if (action === 'reject' && !actionComment.value.trim()) {
    ElMessage.warning('驳回必须填写意见')
    return false
  }
  return true
}

async function onApprove() {
  acting.value = true
  try {
    const comment = actionComment.value.trim() || null
    const id = row.value.bizId
    const uscc = row.value.companyUscc
    switch (row.value.bizType) {
      case 'CERT_APPLICATION':
        await approveApplication(id, comment, uscc); break
      case 'CERT_OP':
        await approveCertOp(id, comment, uscc); break
      case 'COMPANY_OP':
        await approveCompanyOp(id, comment); break
      default:
        ElMessage.warning('当前单据类型不支持批量批准'); return
    }
    ElMessage.success('已批准')
    await afterAction()
  } finally { acting.value = false }
}

async function onReject() {
  if (!requireCommentOnReject('reject')) return
  acting.value = true
  try {
    const comment = actionComment.value.trim()
    const id = row.value.bizId
    const uscc = row.value.companyUscc
    switch (row.value.bizType) {
      case 'CERT_APPLICATION':
        await rejectApplication(id, comment, uscc); break
      case 'CERT_OP':
        await rejectCertOp(id, comment, uscc); break
      case 'COMPANY_OP':
        await rejectCompanyOp(id, comment); break
      default:
        ElMessage.warning('当前单据类型不支持批量驳回'); return
    }
    ElMessage.success('已驳回')
    await afterAction()
  } finally { acting.value = false }
}

async function onIssue() {
  await ElMessageBox.confirm(
      `按申请单 ${row.value.bizNo} 签发证书？私钥由申请方自持，CA 不落盘。`,
      '签发证书', {type: 'warning', confirmButtonText: '确定签发', cancelButtonText: '取消'}
  ).catch(() => false)
  acting.value = true
  try {
    await issueApplication(row.value.bizId, row.value.companyUscc)
    ElMessage.success('签发成功')
    await afterAction()
  } finally { acting.value = false }
}

/** COMPANY_OP 重指派保管人投票（同意/驳回） */
async function onCompanyVote(agree) {
  if (!agree && !actionComment.value.trim()) {
    ElMessage.warning('驳回必须填写投票意见')
    return
  }
  acting.value = true
  try {
    await voteCompanyOp(row.value.bizId, {
      agree, comment: actionComment.value.trim() || undefined
    })
    ElMessage.success(agree ? '已同意' : '已驳回')
    await afterAction()
  } finally { acting.value = false }
}

/** SUB_CA 重建：保管人投票——同意须提交本人份额 */
async function onSubCaVoteAgree() {
  if (!myShare.value?.shareJson) {
    ElMessage.warning('未取到你的根私钥份额，无法同意（请确认你是该公司密钥保管人）')
    return
  }
  acting.value = true
  try {
    await approveSubCaRebuild(row.value.bizId, {
      comment: actionComment.value.trim() || null,
      shareJson: myShare.value.shareJson
    })
    ElMessage.success('已同意并提交你的份额')
    await afterAction()
  } finally { acting.value = false }
}

/** SUB_CA 重建：保管人投票——驳回 */
async function onVoteReject() {
  if (row.value.bizType === 'SUB_CA_REBUILD') {
    if (!actionComment.value.trim()) {
      ElMessage.warning('驳回必须填写意见')
      return
    }
    acting.value = true
    try {
      await rejectSubCaRebuild(row.value.bizId, {comment: actionComment.value.trim()})
      ElMessage.success('已驳回')
      await afterAction()
    } finally { acting.value = false }
  } else {
    await onCompanyVote(false)
  }
}

async function onExecuteRebuild() {
  if (!canUseCollected.value && pastedShares.value < executeThreshold.value) {
    ElMessage.warning(`至少需要粘贴 ${executeThreshold.value} 份份额`)
    return
  }
  const tip = canUseCollected.value
      ? `将用已收集的 ${d('collectedShareCount')} 份份额解封根私钥（仅内存加载），并重新签发二级CA。`
      : '将用手贴的份额解封根私钥（仅内存加载），并重新签发二级CA。'
  await ElMessageBox.confirm(tip + ' 操作不可撤回，确定执行吗？', '执行二级CA重建', {
    type: 'warning', confirmButtonText: '确定执行', cancelButtonText: '取消'
  }).catch(() => false)
  acting.value = true
  try {
    const payload = canUseCollected.value
        ? {useCollectedShares: true}
        : {shareJsons: executeShares.value.map(s => s.trim()).filter(Boolean)}
    await executeSubCaRebuild(row.value.bizId, payload)
    ElMessage.success('已执行二级CA重建')
    await afterAction()
  } finally { acting.value = false }
}

// 领取接入凭证
const receiptVisible = ref(false)
const receipt = ref(null)
async function onTakeReceipt() {
  acting.value = true
  try {
    const res = await takeCompanyOpReceipt(row.value.bizId)
    receipt.value = res.data || {}
    receiptVisible.value = true
    await afterAction()
  } finally { acting.value = false }
}

async function onCancel() {
  await ElMessageBox.confirm(
      `确定撤回单据 ${row.value.bizNo} 吗？`, '撤回',
      {type: 'warning', confirmButtonText: '确定撤回', cancelButtonText: '取消'}
  ).catch(() => false)
  acting.value = true
  try {
    const id = row.value.bizId
    const uscc = row.value.companyUscc
    switch (row.value.bizType) {
      case 'CERT_APPLICATION': await cancelApplication(id, uscc); break
      case 'CERT_OP': await cancelCertOp(id, uscc); break
      case 'COMPANY_OP': await cancelCompanyOp(id); break
      case 'SUB_CA_REBUILD': await cancelSubCaRebuild(id); break
    }
    ElMessage.success('已撤回')
    await afterAction()
  } finally { acting.value = false }
}

async function onUrge() {
  await ElMessageBox.confirm('确定向当前处理人发送催办提醒？', '催办', {type: 'warning'}).catch(() => false)
  acting.value = true
  try {
    const id = row.value.bizId
    const uscc = row.value.companyUscc
    switch (row.value.bizType) {
      case 'CERT_APPLICATION': await urgeApplication(id, uscc); break
      case 'CERT_OP': await urgeCertOp(id, uscc); break
      case 'COMPANY_OP': await urgeCompanyOp(id); break
      case 'SUB_CA_REBUILD': await urgeSubCaRebuild(id); break
    }
    ElMessage.success('已发送催办提醒')
    await afterAction()
  } finally { acting.value = false }
}

async function load() {
  loading.value = true
  try {
    const res = await getWorkflowTodoBoard()
    const data = res.data || {}
    pending.value = data.pending || []
    initiated.value = data.initiated || []
    done.value = data.done || []
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.todo-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.todo-tabs :deep(.el-tabs__header) {
  padding-left: 16px;
  margin-right: 16px;
}

.summary-card {
  border-left: 4px solid var(--his-primary, #0a3d6b);
}

.summary-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.summary-item {
  flex: 1 1 160px;
  min-width: 140px;
  padding: 12px 16px;
  border: 1px solid #eef3f9;
  border-radius: 8px;
  background: #f8fbfe;
  cursor: pointer;
  transition: all 0.2s;
}

.summary-item:hover { border-color: #409eff; }
.summary-item.active { border-color: #409eff; background: #ecf5ff; }

.summary-num {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}
.summary-item.active .summary-num { color: #409eff; }
.summary-label { font-size: 13px; color: #909399; margin-top: 2px; }

.summary-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.tab-badge { transform: translateY(-1px); }

.drawer-body {
  padding-bottom: 24px;
  max-height: 75vh;
  overflow-y: auto;
}

.block-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.pem-block {
  margin: 0;
  max-height: 120px;
  overflow: auto;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.trail :deep(.el-timeline-item__timestamp) {
  color: #909399;
  font-size: 12px;
}
.trail-step { font-weight: 600; color: #303133; }
.trail-who { font-size: 13px; color: #606266; margin-top: 2px; }
.trail-comment { font-size: 13px; color: #909399; margin-top: 2px; }

.action-zone {
  margin-top: 20px;
  padding: 14px;
  background: #f8fbfe;
  border: 1px solid #e6f0fa;
  border-radius: 8px;
}
.action-buttons {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.empty-tip {
  color: #909399;
  font-size: 13px;
  padding: 8px 0 4px;
}
</style>
