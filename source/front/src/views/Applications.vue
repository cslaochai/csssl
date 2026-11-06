<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="公司">
            <el-select v-model="query.companyUscc" clearable placeholder="全部" style="width: 280px"
                       @change="handleSearch">
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
              <el-option label="待审核" value="PENDING"/>
              <el-option label="已批准" value="APPROVED"/>
              <el-option label="已驳回" value="REJECTED"/>
              <el-option label="已签发" value="ISSUED"/>
              <el-option label="已撤销" value="CANCELLED"/>
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input
                v-model="query.keyword"
                clearable
                placeholder="申请人/申请单号"
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
          <el-button v-perm="'app:submit'" type="primary" @click="openSubmit">提交申请</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡：RA 申请台账（审批/签发动作已收敛到「我的待办」，本页只看不改） -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column class-name="cell-mono" label="申请单号" prop="applyNo" show-overflow-tooltip width="180"/>
        <el-table-column label="申请人类别" width="100">
          <template #default="{ row }">{{ row.applicantType === 'INSTITUTION' ? '单位' : '个人' }}</template>
        </el-table-column>
        <el-table-column label="申请人" min-width="140" prop="applicantName" show-overflow-tooltip/>
        <el-table-column label="所属公司" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ companyNames[row.companyUscc] || row.companyUscc || '-' }}</template>
        </el-table-column>
        <el-table-column label="模板" width="100">
          <template #default="{ row }">{{ templateName(row.templateId) }}</template>
        </el-table-column>
        <el-table-column label="主体DN" min-width="180" prop="subjectDn" show-overflow-tooltip/>
        <el-table-column label="提交人" width="120">
          <template #default="{ row }">{{ row.appliedByName || '无' }}</template>
        </el-table-column>
        <el-table-column label="指派审批人" show-overflow-tooltip width="120">
          <template #default="{ row }">{{ row.assigneeRealName || '无' }}</template>
        </el-table-column>
        <el-table-column label="审核人" width="100">
          <template #default="{ row }">{{ row.reviewedByName }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="180">
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
                  <el-dropdown-item
                      v-for="a in moreRowActions(row)"
                      :key="a.label"
                      :command="a"
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

    <!-- 提交申请 -->
    <el-dialog v-model="submitVisible" :close-on-click-modal="false" title="提交证书申请" width="680px">
      <el-form :model="submitForm" label-width="150px">
        <el-form-item label="目标公司" required>
          <el-select v-model="submitCompany" placeholder="选择公司" style="width: 100%" @change="onSubmitCompanyChange">
            <el-option
                v-for="o in companyOptions"
                :key="o.uscc"
                :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                :value="o.uscc"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="申请人类别" required>
          <el-radio-group v-model="submitForm.applicantType">
            <el-radio-button value="INSTITUTION">单位</el-radio-button>
            <el-radio-button value="PERSON">个人</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="申请人/公司名称" required>
          <el-input v-model="submitForm.applicantName"
                    :placeholder="submitForm.applicantType === 'INSTITUTION' ? '如 医保前置系统/结算经办' : '如 张三'"/>
        </el-form-item>
        <el-form-item label="证书模板" required>
          <el-select v-model="submitForm.templateId" placeholder="选择证书模板" style="width: 100%">
            <el-option v-for="t in templates" :key="t.id" :label="`${t.templateName}（${t.templateCode}）`"
                       :value="t.id"/>
          </el-select>
        </el-form-item>
        <el-form-item label="主体CN" required>
          <el-input v-model="submitCn" placeholder="如 张三 / his.yibao.local"/>
        </el-form-item>
        <el-form-item label="主体O" required>
          <el-input v-model="submitO" disabled placeholder="取所选公司受控主体（自动）"/>
          <div class="dn-tip">O 由所选公司档案受控，禁手填防冒名。</div>
        </el-form-item>
        <el-form-item label="员工编号（OU）" required>
          <el-input v-model="submitOu" placeholder="必填：与 HIS 员工编号一致，同一公司内唯一（重名人员靠它区分）"/>
        </el-form-item>
        <el-form-item label="主体别名（SAN）">
          <el-input v-model="submitForm.san" placeholder="逗号分隔：DNS或IP"/>
        </el-form-item>
        <el-form-item label="公钥PEM" required>
          <el-input v-model="submitForm.publicKeyPem" :rows="5"
                    placeholder="申请方自持私钥，仅提交公钥（-----BEGIN PUBLIC KEY----- ...）"
                    type="textarea"/>
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="submitForm.description"/>
        </el-form-item>
        <el-form-item label="审批人" required>
          <el-select v-model="submitAssignee" placeholder="从流程节点筛选出的审批人中选择" style="width: 100%">
            <el-option
                v-for="u in assigneeOptions"
                :key="u.id"
                :label="`${u.realName}（${u.roleName }）`"
                :value="u.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="submitVisible = false">取消</el-button>
          <el-button :loading="submitting" type="primary" @click="onSubmit">提交</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 申请单详情（流程图 + 审批轨迹；审批动作请前往「我的待办」） -->
    <el-dialog v-model="detailVisible" title="申请单详情" width="900px">
      <el-alert v-if="detail && detail.status === 'PENDING'" :closable="false" :title="nextApproverTitle" show-icon
                style="margin-bottom: 12px" type="warning"/>
      <el-alert v-if="detail && detail.status === 'REJECTED'" :closable="false" show-icon
                style="margin-bottom: 12px" type="error"
                :title="`该申请已被驳回${detail.reviewedByName ? '（驳回人：' + detail.reviewedByName + '）' : ''}`"
                :description="detail.reviewComment || '未填写驳回意见'"/>
      <el-alert v-if="detail && detail.status === 'CANCELLED'" :closable="false" show-icon
                style="margin-bottom: 12px" type="info" title="该申请已被提交人撤销"/>
      <WorkflowDiagram :diagram="diagram" style="margin-bottom: 16px"/>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="申请单号">{{ detail?.applyNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="申请人类别">
          {{ detail?.applicantType === 'INSTITUTION' ? '单位' : '个人' }}
        </el-descriptions-item>
        <el-descriptions-item label="申请人">{{ detail?.applicantName }}</el-descriptions-item>
        <el-descriptions-item label="所属公司">
          {{ companyNames[detail?.companyUscc] || detail?.companyUscc || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="证书模板">
          {{ detail ? templateName(detail.templateId) : '' }}
        </el-descriptions-item>
        <el-descriptions-item label="主体DN" :span="2">{{ detail?.subjectDn }}</el-descriptions-item>
        <el-descriptions-item label="主体别名（SAN）" :span="2">{{ detail?.san || '-' }}</el-descriptions-item>
        <el-descriptions-item label="公钥PEM" :span="2">
          <pre v-if="detail?.publicKeyPem" class="pem-block">{{ detail.publicKeyPem }}</pre>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="签发证书">
          {{ detail?.issuedCertId ? `#${detail.issuedCertId}（证书管理可查/下载）` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="trail-title">审批记录</div>
      <el-table v-if="detailTrail.length" :data="detailTrail" border size="small">
        <el-table-column label="环节" width="120">
          <template #default="{ row: t }">
            <el-tag :type="trailType(t.step)" size="small">{{ trailStepText(t.step) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="130">
          <template #default="{ row: t }">
            {{ t.operatorRealName || '—' }}
            <span v-if="t.operatorRole" class="trail-role">（{{ roleText(t.operatorRole) }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="处理时间" prop="createdAt" width="170"/>
        <el-table-column label="状态变化" width="150">
          <template #default="{ row: t }">
            {{ t.fromStatus ? `${statusText(t.fromStatus)} → ${statusText(t.toStatus)}` : statusText(t.toStatus) }}
          </template>
        </el-table-column>
        <el-table-column label="意见/备注" min-width="180">
          <template #default="{ row: t }">
            <span :class="{ 'trail-reject-text': t.step === 'REJECT' }">{{ t.comment || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <span v-else class="trail-empty">暂无审批记录</span>

      <template #footer>
        <div class="dialog-footer">
          <el-button v-if="canUrge(detail)" :loading="urging" type="warning" @click="onUrge(detail)">催办审批人</el-button>
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {ArrowDown, Refresh, Search} from '@element-plus/icons-vue'
import {
  cancelApplication,
  companyNameMap,
  detailApplication,
  listCompaniesCached,
  listFlowCandidates,
  listTrail,
  pageApplications,
  pageTemplates,
  submitApplication,
  urgeApplication
} from '@/api/system'
import {getWorkflowDiagram} from '@/api/workflow'
import WorkflowDiagram from '@/views/WorkflowDiagram.vue'
import {hasPerm} from '@/lib/perm'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'

const route = useRoute()
const router = useRouter()

// RA 证书申请流程编码（候选人与提交起流程都用它）
const FLOW_CODE = 'raApplyFlow'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const templates = ref([])
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, companyUscc: '', status: '', keyword: ''})

// 提交表单：审批人（流程首个审批节点候选，单选）
const assigneeOptions = ref([])
const submitAssignee = ref(null)

const detailVisible = ref(false)
const detail = ref(null)
const detailTrail = ref([])
const diagram = ref(null)
const urging = ref(false)
const submitting = ref(false)

const submitForm = reactive({
  applicantType: 'PERSON', applicantName: '', templateId: null,
  subjectDn: '', san: '', publicKeyPem: '', description: ''
})

function templateName(id) {
  return templates.value.find(t => t.id === id)?.templateName || `#${id}`
}

function statusText(s) {
  return {PENDING: '待审核', APPROVED: '已批准', REJECTED: '已驳回', ISSUED: '已签发', CANCELLED: '已撤销'}[s] || s
}

function statusTag(s) {
  return {PENDING: 'warning', APPROVED: '', REJECTED: 'danger', ISSUED: 'success', CANCELLED: 'info'}[s] || ''
}

async function loadTemplates() {
  const res = await pageTemplates({pageNo: 1, pageSize: 100})
  templates.value = res.data.records || []
}

async function load() {
  loading.value = true
  try {
    const res = await pageApplications({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      companyUscc: query.companyUscc || undefined,
      status: query.status || undefined,
      keyword: query.keyword || undefined
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

// 两卡式列表页：表格只设最大高度，超高内部滚动
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

// 公司域由页面显式选择：列表可留空=跨公司只读，提交按行归属公司
const companyOptions = ref([])
const companyNames = ref({})
const submitCompany = ref('')
const submitCn = ref('')
const submitO = ref('')
const submitOu = ref('')

const submitVisible = ref(false)

function submitSubjectOOf(uscc) {
  return companyOptions.value.find(o => o.uscc === uscc)?.subjectO || ''
}

function onSubmitCompanyChange() {
  submitO.value = submitSubjectOOf(submitCompany.value)
}

function openSubmit() {
  Object.assign(submitForm, {
    applicantType: 'PERSON', applicantName: '', templateId: null,
    subjectDn: '', san: '', publicKeyPem: '', description: ''
  })
  submitCompany.value = query.companyUscc
      || companyOptions.value.find(o => o.status === 'ACTIVE')?.uscc
      || companyOptions.value[0]?.uscc || ''
  submitCn.value = ''
  submitO.value = submitSubjectOOf(submitCompany.value)
  submitOu.value = ''
  submitAssignee.value = null
  assigneeOptions.value = []
  submitVisible.value = true
  listFlowCandidates(FLOW_CODE).then(res => {
    assigneeOptions.value = res.data || []
  }).catch(() => {})
}

async function onSubmit() {
  const cn = submitCn.value.trim()
  const o = submitO.value.trim()
  const ou = submitOu.value.trim()
  if (!submitCompany.value) {
    ElMessage.warning('请选择目标公司')
    return
  }
  if (!cn || !o) {
    ElMessage.warning('请填写主体CN（主体O 取所选公司受控主体）')
    return
  }
  if (!ou) {
    ElMessage.warning('请填写员工编号（OU）：同一公司内唯一，重名人员靠它区分')
    return
  }
  submitForm.subjectDn = `CN=${cn},OU=${ou},O=${o}`
  if (!submitForm.applicantName.trim() || !submitForm.subjectDn.trim() || !submitForm.publicKeyPem.trim() || !submitForm.templateId) {
    ElMessage.warning('请完整填写申请信息（含公钥PEM）')
    return
  }
  if (!submitAssignee.value) {
    ElMessage.warning('请选择审批人（候选范围由流程节点配置决定）')
    return
  }
  submitting.value = true
  try {
    await submitApplication({...submitForm, assigneeUserId: submitAssignee.value}, submitCompany.value)
    ElMessage.success('申请已提交，等待审批人在「我的待办」中处理')
    submitVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

function onDetail(row) {
  detail.value = row
  detailVisible.value = true
  diagram.value = null
  detailTrail.value = []
  try {
    getWorkflowDiagram('CERT_APPLICATION', row.status, row.assigneeRealName).then(res => {
      diagram.value = res.data || null
    })
  } catch (e) {
  }
  detailApplication(row.id).then(res => {
    if (res?.data) detail.value = res.data
  }).catch(() => {})
  listTrail(row.id).then(res => {
    detailTrail.value = (res?.data || []).filter(t => t.step !== 'URGE')
  }).catch(() => {})
}

async function onUrge(row) {
  if (!row) return
  await ElMessageBox.confirm(`向${row.assigneeRealName ? '审批人 ' + row.assigneeRealName : '本公司审核员'}发送催办提醒？`, '催办', {type: 'warning'})
  urging.value = true
  try {
    await urgeApplication(row.id, row.companyUscc)
    ElMessage.success('已发送催办提醒')
  } finally {
    urging.value = false
  }
}

function canUrge(row) {
  return !!row && !!row.urgeable && hasPerm('app:urge')
}

const nextApproverTitle = computed(() => {
  const d = detail.value
  if (!d) return ''
  if (d.status === 'PENDING') {
    return d.assigneeRealName
        ? `当前环节：等待【${d.assigneeRealName}】审批`
        : '当前环节：等待审批人处理（未指定具体人）'
  }
  if (d.status === 'APPROVED') return '当前环节：已批准，等待签发员在「我的待办」中签发'
  if (d.status === 'ISSUED') return '当前环节：已签发'
  if (d.status === 'REJECTED') return '当前环节：已驳回'
  if (d.status === 'CANCELLED') return '当前环节：已撤销'
  return ''
})

function trailStepText(step) {
  return {
    SUBMIT: '提交申请',
    ASSIGN: '指定审批人',
    APPROVE: '审核批准',
    REJECT: '审核驳回',
    ISSUE: '签发证书',
    CANCEL: '撤销申请',
    URGE: '催办'
  }[step] || step
}

function trailType(step) {
  return {
    SUBMIT: 'primary',
    ASSIGN: 'info',
    APPROVE: 'success',
    REJECT: 'danger',
    ISSUE: 'success',
    CANCEL: 'info',
    URGE: 'warning'
  }[step] || ''
}

const ROLE_TEXT = {
  ADMIN: '系统管理员',
  OPERATOR: 'CA操作员',
  APPROVER: '审核员',
  AUDITOR: '审计员',
  SECURITY_ADMIN: '安全管理员',
  KEY_ADMIN: '密钥管理员',
  COMPANY_ADMIN: '公司管理员',
  COMPANY_APPROVER: '公司审批员',
  USER_ADMIN: '用户与角色管理员',
  RA_ADMIN: 'RA业务管理员',
  AUDIT_ADMIN: '审计管理员'
}

function roleText(code) {
  return ROLE_TEXT[code] || code || ''
}

// 操作列：只留发起人动作（详情/催办/撤销）；审批与签发已收敛到「我的待办」
const ACTION_VISIBLE = 3

function appActions(row) {
  const acts = []
  acts.push({label: '详情', run: () => onDetail(row)})
  if (row.status === 'PENDING' && row.urgeable && hasPerm('app:urge')) {
    acts.push({label: '催办', type: 'warning', run: () => onUrge(row)})
  }
  if (['PENDING', 'APPROVED'].includes(row.status) && hasPerm('app:cancel')) {
    acts.push({label: '撤销', type: 'info', run: () => onCancel(row)})
  }
  return acts
}

async function onCancel(row) {
  await ElMessageBox.confirm(`确定撤销申请单 ${row.applyNo} 吗？`, '提示', {type: 'warning'})
  await cancelApplication(row.id, row.companyUscc)
  ElMessage.success('申请已撤销')
  load()
}

function visibleRowActions(row) {
  return appActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return appActions(row).slice(ACTION_VISIBLE)
}

onMounted(async () => {
  loadTemplates()
  companyOptions.value = await listCompaniesCached()
  companyNames.value = await companyNameMap()
  load()
  // 兼容旧深链：?applyId=xx 打开详情（待办已改为抽屉内联处理，不再跳转过来）
  const applyId = route.query.applyId
  if (applyId) {
    try {
      const res = await detailApplication(applyId)
      onDetail(res.data)
    } catch (e) { /* 无权查看时留在列表 */ }
    router.replace({path: route.path})
  }
})
</script>

<style scoped>
.dn-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
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

.trail-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.trail-role {
  color: #909399;
  font-size: 12px;
}

.trail-reject-text {
  color: #f56c6c;
}

.trail-empty {
  color: #909399;
  font-size: 13px;
  padding: 8px 0 4px;
}

.empty-tip {
  color: #909399;
  font-size: 13px;
  padding: 8px 0 4px;
}
</style>
