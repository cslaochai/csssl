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
        <el-table-column prop="uscc" label="统一社会信用代码" class-name="cell-mono" min-width="200" show-overflow-tooltip>
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
        <el-table-column label="根密钥状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.rootKeyState" size="small" :type="rootKeyTag(row.rootKeyState)">{{ rootKeyText(row.rootKeyState) }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="根CA有效期" min-width="170">
          <template #default="{ row }">{{ validity(row.rootValidFrom, row.rootValidTo) }}</template>
        </el-table-column>
        <el-table-column label="二级CA有效期（SUB_CA）" min-width="170">
          <template #default="{ row }">{{ validity(row.subCaValidFrom, row.subCaValidTo) }}</template>
        </el-table-column>
        <el-table-column label="接入ID（App ID）" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.apiAppId">
              <span class="cell-mono">{{ row.apiAppId }}</span>
              <div class="cell-sub">{{ row.apiClientStatus === 1 ? '接入启用' : '接入停用' }}</div>
            </template>
            <span v-else class="cell-sub">未生成</span>
          </template>
        </el-table-column>
        <el-table-column label="联系人" min-width="140">
          <template #default="{ row }">
            {{ row.contact || '-' }}
            <div v-if="row.contactPhone" class="cell-sub">{{ maskPhone(row.contactPhone) }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" label="操作" width="220" fixed="right">
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

    <!-- 新建租户 -->
    <el-dialog v-model="createVisible" title="新建租户" width="540px" :close-on-click-modal="false">
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
          <el-input v-model="form.subjectO" placeholder="该租户全部证书的主体O，如 HOSPITAL-A" />
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
          <el-button :disabled="creating" @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="onCreate">
            {{ creating ? '正在生成独立根CA…' : '创建租户' }}
          </el-button>
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

    <!-- 接入凭证（仅展示一次） -->
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
        <el-descriptions-item label="绑定租户">{{ cred.tenantName || cred.tenantKey }}</el-descriptions-item>
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
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  pageTenants, createTenant, suspendTenant, resumeTenant, closeTenant, resetTenantApiCredential, listTenantsCached
} from '@/api/system'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'
import { hasPerm } from '@/lib/perm'
import { copyText } from '@/lib/clipboard'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', keyword: '' })

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()

const createVisible = ref(false)
const creating = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const credVisible = ref(false)
const cred = ref(null)
const formRef = ref()
const form = reactive({
  uscc: '', tenantName: '', subjectO: '', algorithm: 'SM2', contact: '', contactPhone: '', description: ''
})

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

function tenantActions(row) {
  const acts = [{ label: '详情', run: () => onRowDetail(row) }]
  if (hasPerm('tenant:credential')) {
    acts.push({
      label: row.apiAppId ? '重置接入密钥' : '生成接入凭证',
      type: row.apiAppId ? 'primary' : 'success',
      run: () => onResetCredential(row)
    })
  }
  if (row.status === 'ACTIVE' && hasPerm('tenant:suspend')) {
    acts.push({ label: '暂停', type: 'warning', run: () => onSuspend(row) })
  }
  if (row.status === 'SUSPENDED' && hasPerm('tenant:resume')) {
    acts.push({ label: '恢复', type: 'success', run: () => onResume(row) })
  }
  if (row.status !== 'CLOSED' && hasPerm('tenant:close')) {
    acts.push({ label: '关闭', type: 'danger', run: () => onClose(row) })
  }
  return acts
}

function visibleRowActions(row) {
  return tenantActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return tenantActions(row).slice(ACTION_VISIBLE)
}

function showCredential(payload) {
  cred.value = {
    appId: payload.appId,
    secret: payload.secret,
    tenantKey: payload.tenantKey,
    tenantName: payload.tenantName
  }
  credVisible.value = true
}

async function onResetCredential(row) {
  await ElMessageBox.confirm(
    row.apiAppId
      ? `重置后「${row.tenantName}」的旧密钥立即失效，外部系统需同步换成新密钥。`
      : `将为「${row.tenantName}」生成 App ID 与接入密钥。`,
    row.apiAppId ? '重置接入密钥' : '生成接入凭证',
    { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
  )
  const res = await resetTenantApiCredential(row.tenantKey)
  showCredential({ ...res.data, tenantKey: row.tenantKey, tenantName: row.tenantName })
  await load()
}

async function onCopy(text) {
  const ok = await copyText(text)
  ok ? ElMessage.success('已复制') : ElMessage.error('复制失败，请手动选择复制')
}

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

function openCreate() {
  Object.assign(form, {
    uscc: '', tenantName: '', subjectO: '', algorithm: 'SM2', contact: '', contactPhone: '', description: ''
  })
  createVisible.value = true
}

async function onCreate() {
  await formRef.value.validate()
  creating.value = true
  ElMessage.info('正在生成独立根CA，请稍候…')
  try {
    const res = await createTenant({ ...form })
    ElMessage.success('租户已创建，独立根CA与SUB_CA生成完成')
    createVisible.value = false
    await Promise.all([load(), listTenantsCached(true)])
    showCredential({
      appId: res.data?.apiAppId,
      secret: res.data?.apiSecret,
      tenantKey: res.data?.tenantKey,
      tenantName: form.tenantName
    })
  } finally {
    creating.value = false
  }
}

async function onSuspend(row) {
  await ElMessageBox.confirm(
    `确定暂停租户「${row.tenantName}」吗？暂停后该租户停止签发与新验证请求，已签发证书与CRL仍可查询。`,
    '暂停租户',
    { type: 'warning', confirmButtonText: '确定暂停', cancelButtonText: '取消' }
  )
  await suspendTenant(row.tenantKey)
  ElMessage.success('租户已暂停')
  await Promise.all([load(), listTenantsCached(true)])
}

async function onResume(row) {
  await ElMessageBox.confirm(`确定恢复租户「${row.tenantName}」吗？恢复后该租户业务操作重新开放。`, '恢复租户', { type: 'warning' })
  await resumeTenant(row.tenantKey)
  ElMessage.success('租户已恢复')
  await Promise.all([load(), listTenantsCached(true)])
}

async function onClose(row) {
  // 关闭不可逆：二次输入租户名称确认
  const { value } = await ElMessageBox.prompt(
    `关闭租户「${row.tenantName}」后其根CA停用、不可再签发（不可逆）。请输入租户名称以确认：`,
    '关闭租户',
    {
      type: 'error',
      confirmButtonText: '确定关闭',
      cancelButtonText: '取消',
      inputPlaceholder: `输入 ${row.tenantName}`,
      inputValidator: v => (String(v).trim() === row.tenantName ? true : '租户名称输入不一致')
    }
  )
  if (String(value).trim() !== row.tenantName) return
  await closeTenant(row.tenantKey)
  ElMessage.success('租户已关闭')
  await Promise.all([load(), listTenantsCached(true)])
}

onMounted(load)
</script>

<style scoped>
.font-medium {
  font-weight: 500;
  color: #1e293b;
}
.cred-desc {
  margin-top: 12px;
}
</style>
