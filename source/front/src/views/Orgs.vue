<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="机构状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
              <el-option label="启用" value="ACTIVE" />
              <el-option label="已暂停" value="SUSPENDED" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input
              v-model="query.keyword"
              placeholder="机构标识 / 名称 / 主体O"
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
          <el-button v-perm="'org:create'" type="primary" :icon="Plus" @click="openCreate">新建机构</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="orgKey" label="机构标识" class-name="cell-mono" min-width="130" show-overflow-tooltip />
        <el-table-column prop="orgName" label="机构名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="font-medium">{{ row.orgName }}</div>
            <div v-if="row.subjectO" class="cell-sub">{{ row.subjectO }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="algorithm" label="算法" width="100">
          <template #default="{ row }">{{ row.algorithm || '-' }}</template>
        </el-table-column>
        <el-table-column label="机构状态" width="100">
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
        <el-table-column label="SUB_CA有效期" min-width="170">
          <template #default="{ row }">{{ validity(row.subCaValidFrom, row.subCaValidTo) }}</template>
        </el-table-column>
        <el-table-column prop="contact" label="联系人" width="110">
          <template #default="{ row }">{{ row.contact || '-' }}</template>
        </el-table-column>
        <el-table-column align="right" label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onRowDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'ACTIVE'"
              v-perm="'org:suspend'"
              link
              type="warning"
              size="small"
              @click="onSuspend(row)"
            >暂停</el-button>
            <el-button
              v-if="row.status === 'SUSPENDED'"
              v-perm="'org:resume'"
              link
              type="success"
              size="small"
              @click="onResume(row)"
            >恢复</el-button>
            <el-button
              v-if="row.status !== 'CLOSED'"
              v-perm="'org:close'"
              link
              type="danger"
              size="small"
              @click="onClose(row)"
            >关闭</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的机构记录</div>
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

    <!-- 新建机构 -->
    <el-dialog v-model="createVisible" title="新建机构" width="540px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="机构标识" prop="orgKey">
          <el-input v-model="form.orgKey" placeholder="小写字母数字，如 yibao / huxi，创建后不可修改" />
        </el-form-item>
        <el-form-item label="机构名称" prop="orgName">
          <el-input v-model="form.orgName" placeholder="如 医保结算中心" />
        </el-form-item>
        <el-form-item label="主体O" prop="subjectO">
          <el-input v-model="form.subjectO" placeholder="该机构全部证书的主体O，如 HOSPITAL-A" />
        </el-form-item>
        <el-form-item label="算法" prop="algorithm">
          <el-radio-group v-model="form.algorithm">
            <el-radio-button value="SM2">SM2 国密</el-radio-button>
            <el-radio-button value="RSA2048">RSA 2048</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact" placeholder="机构CA管理员，可选" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="用途/边界说明，可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="creating" @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="onCreate">
            {{ creating ? '正在生成独立根CA…' : '创建机构' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 机构详情（行点击） -->
    <el-dialog v-model="detailVisible" :title="`机构详情：${detail?.orgName || ''}`" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="机构标识">{{ detail?.orgKey }}</el-descriptions-item>
        <el-descriptions-item label="机构名称">{{ detail?.orgName }}</el-descriptions-item>
        <el-descriptions-item label="主体O">{{ detail?.subjectO || '-' }}</el-descriptions-item>
        <el-descriptions-item label="算法">{{ detail?.algorithm || '-' }}</el-descriptions-item>
        <el-descriptions-item label="机构状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="根密钥状态">{{ detail?.rootKeyState ? rootKeyText(detail.rootKeyState) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="根CA有效期">{{ detail ? validity(detail.rootValidFrom, detail.rootValidTo) : '' }}</el-descriptions-item>
        <el-descriptions-item label="SUB_CA有效期">{{ detail ? validity(detail.subCaValidFrom, detail.subCaValidTo) : '' }}</el-descriptions-item>
        <el-descriptions-item label="联系人">{{ detail?.contact || '-' }}</el-descriptions-item>
        <el-descriptions-item label="说明">{{ detail?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail?.createdAt || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { pageOrgs, createOrg, suspendOrg, resumeOrg, closeOrg, listOrgsCached } from '@/api/system'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'

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
const formRef = ref()
const form = reactive({ orgKey: '', orgName: '', subjectO: '', algorithm: 'SM2', contact: '', description: '' })
const rules = {
  orgKey: [
    { required: true, message: '请输入机构标识', trigger: 'blur' },
    { pattern: /^[a-z0-9]{1,32}$/, message: '仅小写字母与数字，长度1-32', trigger: 'blur' }
  ],
  orgName: [{ required: true, message: '请输入机构名称', trigger: 'blur' }],
  subjectO: [{ required: true, message: '请输入主体O', trigger: 'blur' }]
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

async function load() {
  loading.value = true
  try {
    const res = await pageOrgs({
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
  Object.assign(form, { orgKey: '', orgName: '', subjectO: '', algorithm: 'SM2', contact: '', description: '' })
  createVisible.value = true
}

async function onCreate() {
  await formRef.value.validate()
  creating.value = true
  ElMessage.info('正在生成独立根CA，请稍候…')
  try {
    await createOrg({ ...form })
    ElMessage.success('机构已创建，独立根CA与SUB_CA生成完成')
    createVisible.value = false
    await Promise.all([load(), listOrgsCached(true)])
  } finally {
    creating.value = false
  }
}

async function onSuspend(row) {
  await ElMessageBox.confirm(
    `确定暂停机构 ${row.orgKey} 吗？暂停后该机构停止签发与新验证请求，已签发证书与CRL仍可查询。`,
    '暂停机构',
    { type: 'warning', confirmButtonText: '确定暂停', cancelButtonText: '取消' }
  )
  await suspendOrg(row.orgKey)
  ElMessage.success('机构已暂停')
  await Promise.all([load(), listOrgsCached(true)])
}

async function onResume(row) {
  await ElMessageBox.confirm(`确定恢复机构 ${row.orgKey} 吗？恢复后该机构业务操作重新开放。`, '恢复机构', { type: 'warning' })
  await resumeOrg(row.orgKey)
  ElMessage.success('机构已恢复')
  await Promise.all([load(), listOrgsCached(true)])
}

async function onClose(row) {
  // 关闭不可逆：二次输入 orgKey 确认
  const { value } = await ElMessageBox.prompt(
    `关闭机构 ${row.orgKey} 后其根CA停用、不可再签发（不可逆）。请输入机构标识以确认：`,
    '关闭机构',
    {
      type: 'error',
      confirmButtonText: '确定关闭',
      cancelButtonText: '取消',
      inputPlaceholder: `输入 ${row.orgKey}`,
      inputValidator: v => (v === row.orgKey ? true : '机构标识输入不一致')
    }
  )
  if (value !== row.orgKey) return
  await closeOrg(row.orgKey)
  ElMessage.success('机构已关闭')
  await Promise.all([load(), listOrgsCached(true)])
}

onMounted(load)
</script>

<style scoped>
.font-medium {
  font-weight: 500;
  color: #1e293b;
}
</style>
