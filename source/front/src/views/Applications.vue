<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="状态">
            <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px" @change="handleSearch">
              <el-option label="待审核" value="PENDING" />
              <el-option label="已批准" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已签发" value="ISSUED" />
              <el-option label="已撤销" value="CANCELLED" />
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
          <el-tag size="small" type="info" effect="plain">当前域：{{ orgDomain }}</el-tag>
          <el-button v-perm="'app:submit'" type="primary" @click="openSubmit">提交申请</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="applyNo" label="申请单号" class-name="cell-mono" width="180" show-overflow-tooltip />
        <el-table-column label="申请人类别" width="100">
          <template #default="{ row }">{{ row.applicantType === 'INSTITUTION' ? '机构' : '个人' }}</template>
        </el-table-column>
        <el-table-column prop="applicantName" label="申请人/机构" min-width="140" show-overflow-tooltip />
        <el-table-column label="模板" width="100">
          <template #default="{ row }">{{ templateName(row.templateId) }}</template>
        </el-table-column>
        <el-table-column prop="subjectDn" label="主体DN" min-width="180" show-overflow-tooltip />
        <el-table-column label="提交人" width="120">
          <template #default="{ row }">{{ row.appliedByName || row.appliedBy }}</template>
        </el-table-column>
        <el-table-column prop="reviewedBy" label="审核人" width="100">
          <template #default="{ row }">{{ row.reviewedBy || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column align="right" label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" v-perm="'app:approve'" link type="success" size="small" @click="openReview(row, 'approve')">批准</el-button>
            <el-button v-if="row.status === 'PENDING'" v-perm="'app:reject'" link type="danger" size="small" @click="openReview(row, 'reject')">驳回</el-button>
            <el-button v-if="row.status === 'APPROVED'" v-perm="'app:issue'" link type="primary" size="small" @click="onIssue(row)">签发</el-button>
            <el-button v-if="['PENDING', 'APPROVED'].includes(row.status)" v-perm="'app:cancel'" link size="small" @click="onCancel(row)">撤销</el-button>
            <el-button link size="small" @click="onDetail(row)">详情</el-button>
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

    <!-- 提交申请 -->
    <el-dialog v-model="submitVisible" title="提交证书申请（录入员代录）" width="620px" :close-on-click-modal="false">
      <el-form :model="submitForm" label-width="120px">
        <el-form-item label="申请人类别" required>
          <el-radio-group v-model="submitForm.applicantType">
            <el-radio-button value="INSTITUTION">机构</el-radio-button>
            <el-radio-button value="PERSON">个人</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="申请人/机构名称" required>
          <el-input v-model="submitForm.applicantName" :placeholder="submitForm.applicantType === 'INSTITUTION' ? '如 医保前置系统/结算经办' : '如 张三'" />
        </el-form-item>
        <el-form-item label="证书模板" required>
          <el-select v-model="submitForm.templateId" style="width: 100%" placeholder="选择证书模板">
            <el-option v-for="t in templates" :key="t.id" :label="`${t.templateName}（${t.templateCode}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isOrgDomain" label="主体CN" required>
          <el-input v-model="submitCn" placeholder="仅填 CN，如 张三 / his.yibao.local" />
          <div class="dn-tip">机构域「{{ orgDomain }}」：O 取机构主体，无需手填，提交拼为 CN=xxx，O 由后端按机构口径受控附加校验。</div>
        </el-form-item>
        <el-form-item v-else label="主体DN" required>
          <el-input v-model="submitForm.subjectDn" placeholder="如 CN=张三,O=长沙麓康医院,OU=门诊部" />
        </el-form-item>
        <el-form-item label="SAN（可选）">
          <el-input v-model="submitForm.san" placeholder="逗号分隔：DNS或IP" />
        </el-form-item>
        <el-form-item label="公钥PEM" required>
          <el-input v-model="submitForm.publicKeyPem" type="textarea" :rows="5" placeholder="申请方自持私钥，仅提交公钥（-----BEGIN PUBLIC KEY----- ...）" />
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="submitForm.description" />
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
    <el-dialog v-model="reviewVisible" :title="reviewAction === 'approve' ? '批准申请' : '驳回申请'" width="480px" :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        {{ reviewAction === 'approve' ? '批准后该申请可进入签发环节（审核人不得与提交人同一人）。' : '驳回必须填写审核意见。' }}
      </p>
      <el-form label-width="90px">
        <el-form-item label="审核意见">
          <el-input v-model="reviewComment" type="textarea" :rows="3" :placeholder="reviewAction === 'approve' ? '可选' : '必填，如 材料不全'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="reviewVisible = false">取消</el-button>
          <el-button :type="reviewAction === 'approve' ? 'success' : 'danger'" :loading="submitting" @click="onReviewConfirm">
            {{ reviewAction === 'approve' ? '批准' : '驳回' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="申请单详情" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="申请单号">{{ detail?.applyNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="申请人类别">{{ detail?.applicantType === 'INSTITUTION' ? '机构' : '个人' }}</el-descriptions-item>
        <el-descriptions-item label="申请人/机构">{{ detail?.applicantName }}</el-descriptions-item>
        <el-descriptions-item label="证书模板">{{ detail ? templateName(detail.templateId) : '' }}</el-descriptions-item>
        <el-descriptions-item label="主体DN">{{ detail?.subjectDn }}</el-descriptions-item>
        <el-descriptions-item label="SAN">{{ detail?.san || '-' }}</el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="提交人/时间">{{ detail ? nameLabel(detail.appliedBy, detail.appliedByName) : '' }} / {{ detail?.appliedAt }}</el-descriptions-item>
        <el-descriptions-item label="审核人/时间">{{ detail ? nameLabel(detail.reviewedBy, detail.reviewedByName) : '-' }} / {{ detail?.reviewedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审核意见">{{ detail?.reviewComment || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import {
  pageApplications, submitApplication, approveApplication, rejectApplication,
  issueApplication, cancelApplication, pageTemplates
} from '@/api/system'
import { currentOrg } from '@/lib/org'
import { listOrgsCached } from '@/api/system'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const templates = ref([])
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', keyword: '' })

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()

// 机构域：列表/审批/签发请求由 request 封装自动附带 orgKey=currentOrg()
const orgDomain = ref(currentOrg())
// 主体 DN 的 O 由受理机构受控（前端拼 DN；subjectO 从机构注册表取，禁手填防冒名）
const orgSubjectO = ref('')
const isOrgDomain = computed(() => !!orgDomain.value)
const submitCn = ref('')

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
  return { PENDING: '待审核', APPROVED: '已批准', REJECTED: '已驳回', ISSUED: '已签发', CANCELLED: '已撤销' }[s] || s
}
function statusTag(s) {
  return { PENDING: 'warning', APPROVED: '', REJECTED: 'danger', ISSUED: 'success', CANCELLED: 'info' }[s] || ''
}

// 展示「真实姓名（登录账号）」；历史数据无真实姓名时回退为登录账号；都缺则 -
function nameLabel(by, byName) {
  if (!by && !byName) return '-'
  if (byName && by && byName !== by) return `${byName}（${by}）`
  return byName || by
}

async function loadTemplates() {
  const res = await pageTemplates({ pageNo: 1, pageSize: 100 })
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
  submitVisible.value = true
}

async function onSubmit() {
  if (isOrgDomain.value) {
    if (!submitCn.value.trim()) {
      ElMessage.warning('请填写主体CN（O 取机构主体，无需手填）')
      return
    }
    if (!orgSubjectO.value) {
      ElMessage.warning('未取到受理机构的受控主体O，请先在机构管理确认机构档案')
      return
    }
    submitForm.subjectDn = `CN=${submitCn.value.trim()},O=${orgSubjectO.value}`
  }
  if (!submitForm.applicantName.trim() || !submitForm.subjectDn.trim() || !submitForm.publicKeyPem.trim() || !submitForm.templateId) {
    ElMessage.warning('请完整填写申请信息（含公钥PEM）')
    return
  }
  submitting.value = true
  try {
    await submitApplication({ ...submitForm })
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
  await ElMessageBox.confirm(`按申请单 ${row.applyNo} 签发证书？私钥由申请方自持，CA 不落盘。`, '提示', { type: 'warning' })
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
  await ElMessageBox.confirm(`确定撤销申请单 ${row.applyNo} 吗？`, '提示', { type: 'warning' })
  await cancelApplication(row.id)
  ElMessage.success('申请已撤销')
  load()
}

function onDetail(row) {
  detail.value = row
  detailVisible.value = true
}

onMounted(async () => {
  loadTemplates()
  load()
  if (orgDomain.value) {
    const hit = (await listOrgsCached()).find(o => o.orgKey === orgDomain.value)
    orgSubjectO.value = hit?.subjectO || ''
  }
})
</script>

<style scoped>
.dn-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
