<template>
  <div>
    <el-tabs v-model="activeTab" class="crl-tabs" @tab-change="onTabChange">
      <!-- 证书申请（RA 提交-审核-签发） -->
      <el-tab-pane label="证书申请" name="apply">
        <!-- 查询卡 -->
        <el-card ref="queryCardRef" class="query-card" shadow="never">
          <div class="query-wrap">
            <el-form :model="query" inline>
              <el-form-item label="状态">
                <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px"
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
                    placeholder="申请人/申请单号"
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
              <el-tag size="small" type="info" effect="plain">当前域：{{ tenantDomain }}</el-tag>
              <el-button v-perm="'app:submit'" type="primary" @click="openSubmit">提交申请</el-button>
            </div>
          </div>
        </el-card>

        <!-- 表格卡 -->
        <el-card class="table-card" shadow="never">
          <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
            <el-table-column prop="applyNo" label="申请单号" class-name="cell-mono" width="180" show-overflow-tooltip/>
            <el-table-column label="申请人类别" width="100">
              <template #default="{ row }">{{ row.applicantType === 'INSTITUTION' ? '单位' : '个人' }}</template>
            </el-table-column>
            <el-table-column prop="applicantName" label="申请人/租户" min-width="140" show-overflow-tooltip/>
            <el-table-column label="模板" width="100">
              <template #default="{ row }">{{ templateName(row.templateId) }}</template>
            </el-table-column>
            <el-table-column prop="subjectDn" label="主体DN" min-width="180" show-overflow-tooltip/>
            <el-table-column label="提交人" width="120">
              <template #default="{ row }">{{ row.appliedByName || row.appliedBy }}</template>
            </el-table-column>
            <el-table-column label="审核人" width="100">
              <template #default="{ row }">{{ row.reviewedByName }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column align="right" label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button
                    v-for="a in visibleRowActions(row)"
                    :key="a.label"
                    link
                    size="small"
                    :type="a.type || 'primary'"
                    @click="a.run()"
                >{{ a.label }}
                </el-button>
                <el-dropdown v-if="moreRowActions(row).length" trigger="click" @command="cmd => cmd.run()">
                  <el-button link type="primary" size="small">
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
          <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的申请记录</div>
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
      </el-tab-pane>

      <!-- 证书操作审批（吊销/挂起/解除挂起双人复核，批准即执行进 CRL） -->
      <el-tab-pane label="证书操作审批" name="certops">
        <el-card class="query-card" shadow="never">
          <div class="query-wrap">
            <el-form :model="opQuery" inline>
              <el-form-item label="状态">
                <el-select v-model="opQuery.status" placeholder="全部" clearable style="width: 130px"
                           @change="handleOpSearch">
                  <el-option label="待审批" value="PENDING"/>
                  <el-option label="已执行" value="EXECUTED"/>
                  <el-option label="已驳回" value="REJECTED"/>
                  <el-option label="已撤销" value="CANCELLED"/>
                </el-select>
              </el-form-item>
              <el-form-item label="操作类型">
                <el-select v-model="opQuery.opType" placeholder="全部" clearable style="width: 130px"
                           @change="handleOpSearch">
                  <el-option label="吊销" value="REVOKE"/>
                  <el-option label="挂起" value="SUSPEND"/>
                  <el-option label="解除挂起" value="UNSUSPEND"/>
                  <el-option label="证书更新" value="RENEW"/>
                  <el-option label="密钥更新" value="REKEY"/>
                </el-select>
              </el-form-item>
              <el-form-item label="关键字">
                <el-input
                    v-model="opQuery.keyword"
                    placeholder="主体CN / 序列号 / 单号"
                    clearable
                    style="width: 220px"
                    @keyup.enter="handleOpSearch"
                    @clear="handleOpSearch"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :icon="Search" @click="handleOpSearch">搜索</el-button>
                <el-button :icon="Refresh" @click="handleOpReset">重置</el-button>
              </el-form-item>
            </el-form>
            <div class="query-actions">
              <el-tag size="small" type="info" effect="plain">当前域：{{ tenantDomain }}</el-tag>
            </div>
          </div>
        </el-card>

        <el-card class="table-card" shadow="never">
          <el-table v-loading="opLoading" :data="opRows" :max-height="tableMaxHeight" stripe style="width: 100%">
            <el-table-column prop="opNo" label="操作单号" class-name="cell-mono" width="180" show-overflow-tooltip/>
            <el-table-column label="操作类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="opTypeTag(row.opType)">{{ opTypeText(row.opType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="certSubjectCn" label="证书主体" min-width="160" show-overflow-tooltip/>
            <el-table-column prop="certSerialHex" label="序列号" class-name="cell-mono" min-width="150"
                             show-overflow-tooltip/>
            <el-table-column label="操作原因" min-width="130">
              <template #default="{ row }">{{ row.reason ? reasonText(row.reason) : '无' }}</template>
            </el-table-column>
            <el-table-column label="新有效期" width="90">
              <template #default="{ row }">{{ row.validityDays ? row.validityDays + ' 天' : '无' }}</template>
            </el-table-column>
            <el-table-column label="发起人" width="130">
              <template #default="{ row }">{{ row.requestedByName }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="opStatusTag(row.status)">{{ opStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="审批人" width="130">
              <template #default="{ row }">{{ row.reviewedByName }}</template>
            </el-table-column>
            <el-table-column align="right" label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button
                    v-for="a in visibleOpActions(row)"
                    :key="a.label"
                    link
                    size="small"
                    :type="a.type || 'primary'"
                    @click="a.run()"
                >{{ a.label }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="opRows.length === 0 && !opLoading" class="empty-tip">暂无匹配的操作单</div>
          <div class="list-footer">
            <el-pagination
                v-model:current-page="opQuery.pageNo"
                v-model:page-size="opQuery.pageSize"
                :page-sizes="PAGE_SIZES"
                :total="opTotal"
                layout="total, sizes, prev, pager, next, jumper"
                @size-change="handleOpSizeChange"
                @current-change="handleOpCurrentChange"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 提交申请 -->
    <el-dialog v-model="submitVisible" title="提交证书申请（录入员代录）" width="680px" :close-on-click-modal="false">
      <el-form :model="submitForm" label-width="150px">
        <el-form-item label="申请人类别" required>
          <el-radio-group v-model="submitForm.applicantType">
            <el-radio-button value="INSTITUTION">单位</el-radio-button>
            <el-radio-button value="PERSON">个人</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="申请人/租户名称" required>
          <el-input v-model="submitForm.applicantName"
                    :placeholder="submitForm.applicantType === 'INSTITUTION' ? '如 医保前置系统/结算经办' : '如 张三'"/>
        </el-form-item>
        <el-form-item label="证书模板" required>
          <el-select v-model="submitForm.templateId" style="width: 100%" placeholder="选择证书模板">
            <el-option v-for="t in templates" :key="t.id" :label="`${t.templateName}（${t.templateCode}）`"
                       :value="t.id"/>
          </el-select>
        </el-form-item>
        <el-form-item label="主体CN" required>
          <el-input v-model="submitCn" placeholder="如 张三 / his.yibao.local"/>
        </el-form-item>
        <el-form-item label="主体O" required>
          <el-input v-model="submitO" :disabled="isTenantDomain"
                    :placeholder="isTenantDomain ? '取租户受控主体（自动）' : '如 长沙麓康医院'"/>
          <div v-if="isTenantDomain" class="dn-tip">租户域「{{ tenantDomain }}」：O 由租户档案受控，禁手填防冒名。</div>
        </el-form-item>
        <el-form-item label="主体OU">
          <el-input v-model="submitOu" placeholder="选填：员工编号等不变信息（科室等易变属性勿填）"/>
        </el-form-item>
        <el-form-item label="主体别名（SAN）">
          <el-input v-model="submitForm.san" placeholder="逗号分隔：DNS或IP"/>
        </el-form-item>
        <el-form-item label="公钥PEM" required>
          <el-input v-model="submitForm.publicKeyPem" type="textarea" :rows="5"
                    placeholder="申请方自持私钥，仅提交公钥（-----BEGIN PUBLIC KEY----- ...）"/>
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="submitForm.description"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="submitVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onSubmit">提交</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 审核（批准/驳回） -->
    <el-dialog v-model="reviewVisible" :title="reviewAction === 'approve' ? '批准申请' : '驳回申请'" width="480px"
               :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        {{
          reviewAction === 'approve' ? '批准后该申请可进入签发环节（审核人不得与提交人同一人）。' : '驳回必须填写审核意见。'
        }}
      </p>
      <el-form label-width="90px">
        <el-form-item label="审核意见">
          <el-input v-model="reviewComment" type="textarea" :rows="3"
                    :placeholder="reviewAction === 'approve' ? '可选' : '必填，如 材料不全'"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="reviewVisible = false">取消</el-button>
          <el-button :type="reviewAction === 'approve' ? 'success' : 'danger'" :loading="submitting"
                     @click="onReviewConfirm">
            {{ reviewAction === 'approve' ? '批准' : '驳回' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 操作单审批（批准=执行进 CRL；驳回=意见必填） -->
    <el-dialog v-model="opReviewVisible" :title="opReviewAction === 'approve' ? '批准操作单（批准即执行）' : '驳回操作单'"
               width="480px"
               :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        {{
          opReviewTarget ? `${opTypeText(opReviewTarget.opType)} ${opReviewTarget.certSubjectCn}（发起人 ${opReviewTarget.requestedByName || opReviewTarget.requestedBy}）` : ''
        }}
        <template v-if="opReviewTarget?.reason">{{ `操作原因：${reasonText(opReviewTarget.reason)}。` }}</template>
        <template v-if="opReviewTarget?.validityDays">{{ `新有效期：${opReviewTarget.validityDays} 天。` }}</template>
        {{
          opReviewAction === 'approve' ? '批准后立即执行：证书状态流转并进 CRL/OCSP，审批人不得与发起人同一人。' : '驳回必须填写审批意见，证书状态不变。'
        }}
      </p>
      <el-form label-width="90px">
        <el-form-item label="审批意见">
          <el-input v-model="opReviewComment" type="textarea" :rows="3"
                    :placeholder="opReviewAction === 'approve' ? '可填写审批说明' : '必填，如 原因不成立'"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="opReviewVisible = false">取消</el-button>
          <el-button :type="opReviewAction === 'approve' ? 'success' : 'danger'" :loading="submitting"
                     @click="onOpReviewConfirm">
            {{ opReviewAction === 'approve' ? '批准并执行' : '驳回' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="申请单详情" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="申请单号">{{ detail?.applyNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="申请人类别">{{
            detail?.applicantType === 'INSTITUTION' ? '单位' : '个人'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="申请人/租户">{{ detail?.applicantName }}</el-descriptions-item>
        <el-descriptions-item label="证书模板">{{
            detail ? templateName(detail.templateId) : ''
          }}
        </el-descriptions-item>
        <el-descriptions-item label="主体DN">{{ detail?.subjectDn }}</el-descriptions-item>
        <el-descriptions-item label="主体别名（SAN）">{{ detail?.san || '-' }}</el-descriptions-item>
        <el-descriptions-item label="公钥PEM">
          <pre v-if="detail?.publicKeyPem" class="pem-block">{{ detail.publicKeyPem }}</pre>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="签发证书">
          {{ detail?.issuedCertId ? `#${detail.issuedCertId}（证书管理可查/下载）` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="提交人/时间">{{ detail?.appliedByName || '-' }}
          / {{ detail?.appliedAt }}
        </el-descriptions-item>
        <el-descriptions-item label="审核人/时间">{{
            detail?.reviewedByName || '-'
          }} / {{ detail?.reviewedAt || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="审核意见">{{ detail?.reviewComment || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {ArrowDown, Refresh, Search} from '@element-plus/icons-vue'
import {
  approveApplication,
  approveCertOp,
  cancelApplication,
  cancelCertOp,
  issueApplication,
  listTenantsCached,
  pageApplications,
  pageCertOps,
  pageTemplates,
  rejectApplication,
  rejectCertOp,
  submitApplication
} from '@/api/system'
import {currentTenant} from '@/lib/tenant'
import {hasPerm} from '@/lib/perm'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const templates = ref([])
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', keyword: ''})

// 页签：证书申请 / 证书操作审批
const activeTab = ref('apply')

// 操作单列表状态（独立分页与筛选，切页签时加载）
const opLoading = ref(false)
const opRows = ref([])
const opTotal = ref(0)
const opQuery = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', opType: '', keyword: ''})
const opReviewVisible = ref(false)
const opReviewAction = ref('approve')
const opReviewComment = ref('')
const opReviewTarget = ref(null)

// 按钮权限点与后端接口校验一一对应；旧会话（无权限缓存）回退角色判断，服务端仍强校验
const username = localStorage.getItem('username') || ''
const canOpApprove = hasPerm('cert:op:approve')

const reasonMap = {
  KEY_COMPROMISE: '密钥泄露',
  CA_COMPROMISE: 'CA泄露',
  AFFILIATION_CHANGED: '隶属关系变更',
  SUPERSEDED: '被新证书替代',
  CESSATION_OF_OPERATION: '停止运营',
  CERTIFICATE_HOLD: '证书挂起',
  PRIVILEGE_WITHDRAWN: '权限撤销',
  UNSPECIFIED: '未指定'
}

function reasonText(r) {
  return reasonMap[r] || r || '未指定'
}

function opTypeText(t) {
  return {REVOKE: '吊销', SUSPEND: '挂起', UNSUSPEND: '解除挂起', RENEW: '证书更新', REKEY: '密钥更新'}[t] || t
}

function opTypeTag(t) {
  return {REVOKE: 'danger', SUSPEND: 'warning', UNSUSPEND: 'success', RENEW: 'primary', REKEY: 'primary'}[t] || ''
}

function opStatusText(s) {
  return {PENDING: '待审批', EXECUTED: '已执行', REJECTED: '已驳回', CANCELLED: '已撤销'}[s] || s
}

function opStatusTag(s) {
  return {PENDING: 'warning', EXECUTED: 'success', REJECTED: 'danger', CANCELLED: 'info'}[s] || ''
}

// 两卡式列表页：表格只设最大高度，超高内部滚动
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

// 租户域：列表/审批/签发请求由 request 封装自动附带 tenantKey=currentTenant()
const tenantDomain = ref(currentTenant())
// 主体 DN 的 O 由受理租户受控（前端拼 DN；subjectO 从租户注册表取，租户域禁手填防冒名）；
// OU 选填，口径=员工编号等不变信息，科室等易变属性不进 DN
const tenantSubjectO = ref('')
const isTenantDomain = computed(() => !!tenantDomain.value)
const submitCn = ref('')
const submitO = ref('')
const submitOu = ref('')

const submitVisible = ref(false)
const reviewVisible = ref(false)
const detailVisible = ref(false)
const reviewAction = ref('approve')
const reviewComment = ref('')
const detail = ref(null)
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

// 展示「真实姓名（登录账号）」；历史数据无真实姓名时回退为登录账号；都缺则 -
function nameLabel(by, byName) {
  if (!by && !byName) return '-'
  if (byName && by && byName !== by) return `${byName}（${by}）`
  return byName || by
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

function openSubmit() {
  Object.assign(submitForm, {
    applicantType: 'PERSON', applicantName: '', templateId: null,
    subjectDn: '', san: '', publicKeyPem: '', description: ''
  })
  submitCn.value = ''
  submitO.value = tenantSubjectO.value || ''
  submitOu.value = ''
  submitVisible.value = true
}

async function onSubmit() {
  const cn = submitCn.value.trim()
  const o = submitO.value.trim()
  const ou = submitOu.value.trim()
  if (!cn || !o) {
    ElMessage.warning('请填写主体CN与主体O')
    return
  }
  if (isTenantDomain.value && !tenantSubjectO.value) {
    ElMessage.warning('未取到受理租户的受控主体O，请先在租户管理确认租户档案')
    return
  }
  submitForm.subjectDn = `CN=${cn},O=${o}` + (ou ? `,OU=${ou}` : '')
  if (!submitForm.applicantName.trim() || !submitForm.subjectDn.trim() || !submitForm.publicKeyPem.trim() || !submitForm.templateId) {
    ElMessage.warning('请完整填写申请信息（含公钥PEM）')
    return
  }
  submitting.value = true
  try {
    await submitApplication({...submitForm})
    ElMessage.success('申请已提交，等待审核员审批')
    submitVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

function openReview(row, action) {
  reviewAction.value = action
  reviewComment.value = ''
  reviewTarget = row
  reviewVisible.value = true
}

let reviewTarget = null

async function onReviewConfirm() {
  if (reviewAction.value === 'reject' && !reviewComment.value.trim()) {
    ElMessage.warning('驳回必须填写审核意见')
    return
  }
  submitting.value = true
  try {
    if (reviewAction.value === 'approve') {
      await approveApplication(reviewTarget.id, reviewComment.value)
      ElMessage.success('已批准，可进入签发环节')
    } else {
      await rejectApplication(reviewTarget.id, reviewComment.value)
      ElMessage.success('已驳回')
    }
    reviewVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function onIssue(row) {
  await ElMessageBox.confirm(`按申请单 ${row.applyNo} 签发证书？私钥由申请方自持，CA 不落盘。`, '提示', {type: 'warning'})
  submitting.value = true
  try {
    const res = await issueApplication(row.id)
    ElMessage.success(`签发成功，证书 id=${res.data.id}（序列号 ${res.data.serialHex}）`)
    load()
  } finally {
    submitting.value = false
  }
}

async function onCancel(row) {
  await ElMessageBox.confirm(`确定撤销申请单 ${row.applyNo} 吗？`, '提示', {type: 'warning'})
  await cancelApplication(row.id)
  ElMessage.success('申请已撤销')
  load()
}

function onDetail(row) {
  detail.value = row
  detailVisible.value = true
}

// 操作列口径：前 3 个平铺，其余收进「更多」下拉（列表页统一模式）
const ACTION_VISIBLE = 3

function appActions(row) {
  const acts = []
  if (row.status === 'PENDING' && hasPerm('app:approve')) {
    acts.push({label: '批准', type: 'success', run: () => openReview(row, 'approve')})
  }
  if (row.status === 'PENDING' && hasPerm('app:reject')) {
    acts.push({label: '驳回', type: 'danger', run: () => openReview(row, 'reject')})
  }
  if (row.status === 'APPROVED' && hasPerm('app:issue')) {
    acts.push({label: '签发', run: () => onIssue(row)})
  }
  acts.push({label: '详情', run: () => onDetail(row)})
  if (['PENDING', 'APPROVED'].includes(row.status) && hasPerm('app:cancel')) {
    acts.push({label: '撤销', run: () => onCancel(row)})
  }
  return acts
}

function visibleRowActions(row) {
  return appActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return appActions(row).slice(ACTION_VISIBLE)
}

// ===== 证书操作审批页签 =====

function onTabChange(name) {
  if (name === 'certops') {
    loadOps()
  }
}

async function loadOps() {
  opLoading.value = true
  try {
    const res = await pageCertOps({
      pageNo: opQuery.pageNo,
      pageSize: opQuery.pageSize,
      status: opQuery.status || undefined,
      opType: opQuery.opType || undefined,
      keyword: opQuery.keyword || undefined
    })
    opRows.value = res.data.records || []
    opTotal.value = res.data.total || 0
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    opLoading.value = false
  }
}

function handleOpSearch() {
  opQuery.pageNo = 1
  loadOps()
}

function handleOpReset() {
  opQuery.status = ''
  opQuery.opType = ''
  opQuery.keyword = ''
  handleOpSearch()
}

function handleOpSizeChange(val) {
  opQuery.pageSize = val
  opQuery.pageNo = 1
  loadOps()
}

function handleOpCurrentChange(val) {
  opQuery.pageNo = val
  loadOps()
}

// 撤销=发起人本人（后端硬校验，前端仅按可判条件显示）
function opActions(row) {
  const acts = []
  if (row.status === 'PENDING' && canOpApprove) {
    acts.push({label: '批准', type: 'success', run: () => openOpReview(row, 'approve')})
  }
  if (row.status === 'PENDING' && canOpApprove) {
    acts.push({label: '驳回', type: 'danger', run: () => openOpReview(row, 'reject')})
  }
  if (row.status === 'PENDING' && row.requestedBy === username) {
    acts.push({label: '撤销', run: () => onOpCancel(row)})
  }
  return acts
}

function visibleOpActions(row) {
  return opActions(row).slice(0, ACTION_VISIBLE)
}

function openOpReview(row, action) {
  opReviewAction.value = action
  opReviewComment.value = ''
  opReviewTarget.value = row
  opReviewVisible.value = true
}

async function onOpReviewConfirm() {
  if (opReviewAction.value === 'reject' && !opReviewComment.value.trim()) {
    ElMessage.warning('驳回必须填写审批意见')
    return
  }
  submitting.value = true
  try {
    if (opReviewAction.value === 'approve') {
      await approveCertOp(opReviewTarget.value.id, opReviewComment.value)
      ElMessage.success('已批准并执行（证书状态已流转，CRL/OCSP 同步生效）')
    } else {
      await rejectCertOp(opReviewTarget.value.id, opReviewComment.value)
      ElMessage.success('已驳回')
    }
    opReviewVisible.value = false
    loadOps()
  } finally {
    submitting.value = false
  }
}

async function onOpCancel(row) {
  await ElMessageBox.confirm(`确定撤销操作单 ${row.opNo} 吗？`, '提示', {type: 'warning'})
  await cancelCertOp(row.id)
  ElMessage.success('操作单已撤销')
  loadOps()
}

onMounted(async () => {
  loadTemplates()
  load()
  if (tenantDomain.value) {
    const hit = (await listTenantsCached()).find(o => o.tenantKey === tenantDomain.value)
    tenantSubjectO.value = hit?.subjectO || ''
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
</style>
