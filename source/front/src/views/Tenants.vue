<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="租户状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
              <el-option label="启用" value="ACTIVE" />
              <el-option label="已暂停" value="SUSPENDED" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input
              v-model="query.keyword"
              placeholder="统一社会信用代码 / 名称 / 主体O"
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
          <el-button v-perm="'tenant:create'" type="primary" :icon="Plus" @click="openCreate">新建租户</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="uscc" label="统一社会信用代码" class-name="cell-mono" width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.uscc || '-' }}</template>
        </el-table-column>
        <el-table-column prop="tenantName" label="租户名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="font-medium">{{ row.tenantName }}</div>
            <div v-if="row.subjectO" class="cell-sub">{{ row.subjectO }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="algorithm" label="算法" width="100">
          <template #default="{ row }">{{ row.algorithm || '-' }}</template>
        </el-table-column>
        <el-table-column label="租户状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="根密钥状态" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.rootKeyState" size="small" :type="rootKeyTag(row.rootKeyState)">{{ rootKeyText(row.rootKeyState) }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="根CA有效期" width="350">
          <template #default="{ row }">{{ validity(row.rootValidFrom, row.rootValidTo) }}</template>
        </el-table-column>
        <el-table-column label="二级CA有效期（SUB_CA）" width="350">
          <template #default="{ row }">{{ validity(row.subCaValidFrom, row.subCaValidTo) }}</template>
        </el-table-column>
        <el-table-column label="接入ID（App ID）" width="250" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.apiAppId">
              <span class="cell-mono">{{ row.apiAppId }}</span>
              <div class="cell-sub">{{ row.apiClientStatus === 1 ? '接入启用' : '接入停用' }}</div>
            </template>
            <span v-else class="cell-sub">未生成</span>
          </template>
        </el-table-column>
        <el-table-column label="联系人" min-width="50">
          <template #default="{ row }">
            {{ row.contact || '-' }}
            <div v-if="row.contactPhone" class="cell-sub">{{ maskPhone(row.contactPhone) }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-for="a in visibleRowActions(row)"
              :key="a.label"
              link
              size="small"
              :type="a.type || 'primary'"
              :disabled="a.disabled"
              @click="a.run()"
            >{{ a.label }}</el-button>
            <el-dropdown v-if="moreRowActions(row).length" trigger="click" @command="cmd => cmd.run()">
              <el-button link type="primary" size="small">
                更多<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="a in moreRowActions(row)"
                    :key="a.label"
                    :command="a"
                    :disabled="a.disabled"
                  >{{ a.label }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的租户记录</div>
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

    <!-- 新建租户（直接执行） -->
    <el-dialog v-model="createVisible" title="新建租户" width="680px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="租户名称" prop="tenantName">
          <el-input v-model="form.tenantName" placeholder="如 医保结算中心" />
        </el-form-item>
        <el-form-item label="统一社会信用代码" prop="uscc">
          <el-input
            v-model="form.uscc"
            maxlength="18"
            class="cell-mono"
            placeholder="18 位，如 91430100MA4L5K2N3T"
            @input="onUsccInput"
          />
        </el-form-item>
        <el-form-item label="主体O" prop="subjectO">
          <el-input v-model="form.subjectO" placeholder="该租户全部证书的主体O，如 长沙麓康医院" />
        </el-form-item>
        <el-form-item label="算法" prop="algorithm">
          <el-radio-group v-model="form.algorithm">
            <el-radio-button value="SM2">SM2 国密</el-radio-button>
            <el-radio-button value="RSA2048">RSA 2048</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact" placeholder="租户CA管理员，可选" />
        </el-form-item>
        <el-form-item label="联系方式" prop="contactPhone">
          <el-input v-model="form.contactPhone" maxlength="11" placeholder="11 位手机号，如 13800001234" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="用途/边界说明，可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="submitting" @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onCreate">新建租户</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 其他变更（暂停/恢复/关闭/重置接入密钥） -->
    <el-dialog v-model="opVisible" :title="opTitle" width="560px" :close-on-click-modal="false">
      <el-alert v-if="opType === 'CLOSE'" type="error" :closable="false" show-icon
        title="关闭后该租户停签发、停 CRL 发布、接入密钥鉴权被拒；根CA密钥与历史证书保留，可再走「申请恢复」回到启用。" class="dialog-tip" />
      <el-alert v-else-if="opType === 'RESUME'" type="warning" :closable="false" show-icon
        title="批准执行后该租户根CA重新可签发并进入 CRL 发布，原接入密钥（App ID）同时恢复可用。" class="dialog-tip" />
      <el-alert v-else-if="opType === 'RESET_CREDENTIAL'" type="warning" :closable="false" show-icon
        title="批准执行后旧接入密钥立即失效，外部系统需换成新密钥；新密钥由发起人一次性领取。" class="dialog-tip" />
      <el-alert v-else type="info" :closable="false" show-icon
        title="提交后需租户审批员批准才执行。" class="dialog-tip" />
      <el-descriptions :column="1" border class="op-desc">
        <el-descriptions-item label="目标租户">{{ opTarget?.tenantName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="统一社会信用代码"><span class="cell-mono">{{ opTarget?.uscc || '-' }}</span></el-descriptions-item>
        <el-descriptions-item label="当前状态">{{ opTarget ? statusText(opTarget.status) : '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px" class="op-form">
        <el-form-item label="变更事由" required>
          <el-input v-model="opReason" type="textarea" :rows="3" maxlength="255" show-word-limit placeholder="业务依据（审批人据此核对），必填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="submitting" @click="opVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onSubmitOp">提交变更单</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 租户详情（行点击） -->
    <el-dialog v-model="detailVisible" :title="`租户详情：${detail?.tenantName || ''}`" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="统一社会信用代码">{{ detail?.uscc || '-' }}</el-descriptions-item>
        <el-descriptions-item label="租户名称">{{ detail?.tenantName }}</el-descriptions-item>
        <el-descriptions-item label="主体O">{{ detail?.subjectO || '-' }}</el-descriptions-item>
        <el-descriptions-item label="算法">{{ detail?.algorithm || '-' }}</el-descriptions-item>
        <el-descriptions-item label="租户状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="根密钥状态">{{ detail?.rootKeyState ? rootKeyText(detail.rootKeyState) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="根CA有效期">{{ detail ? validity(detail.rootValidFrom, detail.rootValidTo) : '' }}</el-descriptions-item>
        <el-descriptions-item label="二级CA有效期（SUB_CA）">{{ detail ? validity(detail.subCaValidFrom, detail.subCaValidTo) : '' }}</el-descriptions-item>
        <el-descriptions-item label="联系人">{{ detail?.contact || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系方式">{{ maskPhone(detail?.contactPhone) }}</el-descriptions-item>
        <el-descriptions-item label="接入ID（App ID）">
          <span v-if="detail?.apiAppId" class="cell-mono">{{ detail.apiAppId }}</span>
          <span v-else>未生成</span>
        </el-descriptions-item>
        <el-descriptions-item label="接入状态">
          {{ detail?.apiAppId ? (detail.apiClientStatus === 1 ? '启用' : '停用') : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="说明">{{ detail?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail?.createdAt || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 接入凭证回执（仅展示一次） -->
    <el-dialog v-model="credVisible" title="接入凭证" width="620px" :close-on-click-modal="false" @closed="cred = null">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="密钥仅展示一次，关闭后无法再次查看；请立即复制到 HIS 等外部系统的配置中。"
      />
      <el-descriptions v-if="cred" :column="1" border class="cred-desc">
        <el-descriptions-item label="接入ID（App ID）">
          <span class="cell-mono">{{ cred.appId }}</span>
          <el-button link type="primary" size="small" @click="onCopy(cred.appId)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="接入密钥">
          <span class="cell-mono">{{ cred.secret }}</span>
          <el-button link type="primary" size="small" @click="onCopy(cred.secret)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="绑定租户">{{ cred.tenantName || cred.tenantUscc }}</el-descriptions-item>
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
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { createTenant, pageTenants, submitTenantOp, listTenantsCached } from '@/api/system'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'
import { hasPerm } from '@/lib/perm'
import { copyText } from '@/lib/clipboard'

const OP_TYPE_LABELS = {
  SUSPEND: '暂停租户',
  RESUME: '恢复租户',
  CLOSE: '关闭租户',
  RESET_CREDENTIAL: '重置接入密钥'
}

// ===== 租户列表 =====
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', keyword: '' })
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()

// ===== 弹窗 =====
const createVisible = ref(false)
const opVisible = ref(false)
const opType = ref('')
const opTarget = ref(null)
const opReason = ref('')
const submitting = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const credVisible = ref(false)
const cred = ref(null)
const formRef = ref()
const form = reactive({
  uscc: '', tenantName: '', subjectO: '', algorithm: 'SM2', contact: '', contactPhone: '', description: ''
})

const opTitle = computed(() => OP_TYPE_LABELS[opType.value] || '租户变更')

// ===== 统一社会信用代码（GB 32100）：18 位，末位为加权和 mod 31 的校验码 =====
const USCC_CHARSET = '0123456789ABCDEFGHJKLMNPQRTUWXY'
const USCC_WEIGHTS = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28]

function isValidUscc(code) {
  const s = String(code || '').toUpperCase()
  if (!/^[0-9A-HJ-NPQRTUWXY]{18}$/.test(s)) return false
  let sum = 0
  for (let i = 0; i < 17; i++) {
    const idx = USCC_CHARSET.indexOf(s[i])
    if (idx < 0) return false
    sum += idx * USCC_WEIGHTS[i]
  }
  return USCC_CHARSET[(31 - (sum % 31)) % 31] === s[17]
}

// 输入即规整：去空格、统一大写（后端仍按校验码复核）
function onUsccInput(val) {
  form.uscc = String(val || '').replace(/\s/g, '').toUpperCase()
}

const rules = {
  tenantName: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  uscc: [
    { required: true, message: '请输入统一社会信用代码', trigger: 'blur' },
    {
      validator: (rule, val, cb) => (isValidUscc(val) ? cb() : cb(new Error('18 位代码或末位校验码不正确'))),
      trigger: 'blur'
    }
  ],
  subjectO: [{ required: true, message: '请输入主体O', trigger: 'blur' }],
  contactPhone: [{
    validator: (rule, val, cb) => (!val || /^1[3-9]\d{9}$/.test(val) ? cb() : cb(new Error('请填写 11 位手机号'))),
    trigger: 'blur'
  }]
}

// 联系人手机号展示掩码（列表/详情；完整号码只在登记时录入，不再回显）
function maskPhone(v) {
  if (!v) return '-'
  const s = String(v)
  if (/^1[3-9]\d{9}$/.test(s)) return s.slice(0, 3) + '****' + s.slice(7)
  return s.length > 4 ? s.slice(0, 2) + '****' + s.slice(-2) : '****'
}

function statusText(s) {
  return { ACTIVE: '启用', SUSPENDED: '已暂停', CLOSED: '已关闭' }[s] || s || '-'
}
function statusTag(s) {
  return { ACTIVE: 'success', SUSPENDED: 'warning', CLOSED: 'info' }[s] || 'info'
}
function rootKeyText(s) {
  return { ONLINE: 'ONLINE 在线', SEALED: 'SEALED 封存', THRESHOLD: 'THRESHOLD 门限分持', SHARES: 'SHARES 门限分持' }[s] || s
}
function rootKeyTag(s) {
  return { ONLINE: 'success', SEALED: 'warning', THRESHOLD: 'primary', SHARES: 'primary' }[s] || 'info'
}
function validity(from, to) {
  if (!from && !to) return '-'
  return `${from || '?'} ~ ${to || '?'}`
}

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
}

// 操作列口径：前 3 个平铺，其余收进「更多」下拉
const ACTION_VISIBLE = 3

// 租户列表操作：全部是「提交变更单」，不直接执行
function tenantActions(row) {
  const acts = [{ label: '详情', run: () => onRowDetail(row) }]
  if (!hasPerm('tenant:op:apply')) return acts
  if (row.status === 'ACTIVE') {
    acts.push({
      label: row.apiAppId ? '申请重置密钥' : '申请生成凭证',
      type: 'primary',
      run: () => openOp('RESET_CREDENTIAL', row)
    })
    acts.push({ label: '申请暂停', type: 'warning', run: () => openOp('SUSPEND', row) })
  }
  if (row.status === 'SUSPENDED') {
    acts.push({ label: '申请恢复', type: 'success', run: () => openOp('RESUME', row) })
  }
  if (row.status === 'CLOSED') {
    // 关闭只停服务不动密钥，同码不能再新建（唯一键占位），要重新服务这家机构只能恢复
    acts.push({ label: '申请恢复', type: 'success', run: () => openOp('RESUME', row) })
  }
  if (row.status === 'ACTIVE') {
    acts.push({ label: '申请关闭', type: 'danger', run: () => openOp('CLOSE', row) })
  }
  return acts
}

function visibleRowActions(row) {
  return tenantActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return tenantActions(row).slice(ACTION_VISIBLE)
}

async function onCopy(text) {
  const ok = await copyText(text)
  ok ? ElMessage.success('已复制') : ElMessage.error('复制失败，请手动选择复制')
}

function showCredential(payload) {
  cred.value = {
    appId: payload.appId,
    secret: payload.secret,
    tenantUscc: payload.tenantUscc,
    tenantName: payload.tenantName,
    secretVersion: payload.secretVersion
  }
  credVisible.value = true
}

// ===== 租户列表加载 =====
async function load() {
  loading.value = true
  try {
    const res = await pageTenants({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
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

// ===== 新建租户（直接执行：建租户即建根并发放接入凭证） =====
function openCreate() {
  Object.assign(form, {
    uscc: '', tenantName: '', subjectO: '', algorithm: 'SM2', contact: '', contactPhone: '', description: ''
  })
  formRef.value?.clearValidate()
  createVisible.value = true
}

async function onCreate() {
  await formRef.value.validate()
  submitting.value = true
  try {
    const res = await createTenant({
      uscc: form.uscc,
      tenantName: form.tenantName,
      subjectO: form.subjectO,
      algorithm: form.algorithm,
      contact: form.contact,
      contactPhone: form.contactPhone,
      description: form.description
    })
    createVisible.value = false
    ElMessage.success('租户已创建，独立根CA与SUB_CA已初始化')
    showCredential(res.data || {})
    await Promise.all([load(), listTenantsCached(true)])
  } finally {
    submitting.value = false
  }
}

// ===== 发起变更（暂停/恢复/关闭/重置接入密钥） =====
function openOp(type, row) {
  opType.value = type
  opTarget.value = row
  opReason.value = ''
  opVisible.value = true
}

async function onSubmitOp() {
  const reason = opReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写变更事由')
    return
  }
  submitting.value = true
  try {
    await submitTenantOp({
      opType: opType.value,
      uscc: opTarget.value.uscc,
      reason
    })
    ElMessage.success(`${opTitle.value}变更单已提交，等待租户审批员批准`)
    opVisible.value = false
    await afterSubmit()
  } finally {
    submitting.value = false
  }
}

// 提交后刷新列表；租户状态在审批通过执行后才会变化
async function afterSubmit() {
  await load()
}

onMounted(load)
</script>

<style scoped>
.font-medium {
  font-weight: 500;
  color: #1e293b;
}
.dialog-tip {
  margin-bottom: 14px;
}
.op-desc {
  margin-bottom: 12px;
}
.cred-desc {
  margin-top: 12px;
}
</style>
