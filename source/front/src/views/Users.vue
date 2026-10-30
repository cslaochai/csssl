<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="角色">
            <el-select v-model="query.roleCode" placeholder="全部" clearable style="width: 150px" @change="handleSearch">
              <el-option
                v-for="r in roleOptions"
                :key="r.roleCode"
                :label="roleLabel(r)"
                :value="r.roleCode"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="isGlobal" label="租户域">
            <el-select v-model="query.tenantKey" placeholder="全部" clearable style="width: 170px" @change="handleSearch">
              <el-option v-for="o in tenantOptions" :key="o.tenantKey" :label="o.tenantName ? `${o.tenantKey}（${o.tenantName}）` : o.tenantKey" :value="o.tenantKey" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
        <div class="query-actions">
          <el-button v-perm="'user:create'" type="primary" :icon="Plus" @click="openCreate">创建管理账号</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="username" label="账号" min-width="130" class-name="cell-mono" />
        <el-table-column prop="realName" label="姓名" min-width="130" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="roleTag(row.roleCode)">{{ roleText(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="归属租户" width="130">
          <template #default="{ row }">
            <el-tag v-if="!row.tenantKey" size="small" type="info">全局</el-tag>
            <span v-else class="mono">{{ tenantNames[row.tenantKey] || row.tenantKey }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最近登录" width="170" />
        <el-table-column align="right" label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onRowDetail(row)">详情</el-button>
            <el-button v-perm="'user:update'" link type="primary" size="small" :disabled="row.username === 'admin'" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-perm="'user:update'"
              link
              size="small"
              :type="row.status === 1 ? 'danger' : 'success'"
              :disabled="row.username === 'admin'"
              @click="toggleStatus(row)"
            >{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的用户记录</div>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑账号' : '创建管理账号'" width="480px" :close-on-click-modal="false">
      <el-form :model="form" label-width="100px">
        <el-form-item label="账号" required>
          <el-input v-model="form.username" :disabled="editing" placeholder="登录账号（创建后不可改）" />
        </el-form-item>
        <el-form-item :label="editing ? '重置口令' : '口令'" required>
          <el-input
            v-model="form.password"
            :placeholder="editing ? '留空则不修改口令' : '至少8位，需同时包含字母和数字'"
            type="password"
            show-password
          />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" placeholder="真实姓名/岗位" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roleCode" style="width: 100%">
            <el-option
              v-for="r in roleOptions"
              :key="r.roleCode"
              :label="roleLabel(r)"
              :value="r.roleCode"
              :disabled="r.status !== 1"
            />
          </el-select>
          <div class="tenant-tip">角色由角色配置页维护：停用角色不可分配，名称/职责以角色配置为准</div>
        </el-form-item>
        <el-form-item v-if="!editing" label="归属租户">
          <template v-if="isGlobal">
            <el-select v-model="form.tenantKey" style="width: 100%">
              <el-option label="全局账号（跨租户，不限定域）" value="" />
              <el-option v-for="o in tenantOptions" :key="o.tenantKey" :label="o.tenantName ? `${o.tenantKey}（${o.tenantName}）` : o.tenantKey" :value="o.tenantKey" />
            </el-select>
            <div class="tenant-tip">租户账号仅能操作本租户数据；全局账号由后端按角色+租户策略管控。</div>
          </template>
          <el-tag v-else size="small" type="warning">租户用户建号恒归本租户：{{ userTenant }}</el-tag>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="禁用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onSave">保存</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 账号详情（行点击） -->
    <el-dialog v-model="detailVisible" :title="`账号详情：${detail?.username || ''}`" width="520px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="账号">{{ detail?.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ detail?.realName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ detail ? roleText(detail.roleCode) : '' }}</el-descriptions-item>
        <el-descriptions-item label="归属租户">{{ tenantNames[detail?.tenantKey] || detail?.tenantKey || '全局（跨租户）' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail?.status === 1 ? '启用' : '禁用' }}</el-descriptions-item>
        <el-descriptions-item label="最近登录">{{ detail?.lastLoginTime || '从未登录' }}</el-descriptions-item>
        <el-descriptions-item label="最近登录IP">{{ detail?.lastLoginIp || '-' }}</el-descriptions-item>
        <el-descriptions-item label="连续失败次数">{{ detail?.failedAttempts ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="锁定至">{{ detail?.lockedUntil || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail?.createdAt || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { pageUsers, createUser, updateUser, listTenantsCached, listRoles, tenantNameMap } from '@/api/system'
import { isGlobalUser, userTenantKey } from '@/lib/tenant'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'

// 角色下拉的兜底清单（接口不可用时页面仍可操作；正常以角色配置页档案为准）
const DEFAULT_ROLES = [
  { roleCode: 'ADMIN', roleName: '系统管理员', status: 1 },
  { roleCode: 'OPERATOR', roleName: 'CA操作员', status: 1 },
  { roleCode: 'APPROVER', roleName: '审核员', status: 1 },
  { roleCode: 'AUDITOR', roleName: '审计员', status: 1 },
  { roleCode: 'SECURITY_ADMIN', roleName: '安全管理员', status: 1 },
  { roleCode: 'KEY_ADMIN', roleName: '密钥管理员', status: 1 }
]
const roleOptions = ref([...DEFAULT_ROLES])
const roleMap = computed(() => Object.fromEntries(roleOptions.value.map(r => [r.roleCode, r])))

function roleLabel(r) {
  return r.status === 1 ? r.roleName : `${r.roleName}（停用）`
}

const isGlobal = ref(isGlobalUser())
const userTenant = ref(userTenantKey())
const tenantNames = ref({})
const tenantOptions = ref([])

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, roleCode: '', tenantKey: '' })

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()
const dialogVisible = ref(false)
const editing = ref(false)
const submitting = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const form = reactive({ id: null, username: '', password: '', realName: '', roleCode: 'OPERATOR', status: 1, tenantKey: '' })

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
}

function roleText(r) {
  return roleMap.value[r]?.roleName || r
}
// 角色 → el-tag 颜色；OPERATOR 给一个真实存在的类型（success），未知角色兜底为 info，
// 绝不能返回空串，否则 el-tag 的 type prop 校验会告警 "got value \"\""
function roleTag(r) {
  return { ADMIN: 'danger', OPERATOR: 'success', APPROVER: 'warning', AUDITOR: 'info', SECURITY_ADMIN: 'primary', KEY_ADMIN: 'warning' }[r] || 'info'
}

async function load() {
  loading.value = true
  try {
    const res = await pageUsers({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      roleCode: query.roleCode || undefined,
      // 全局用户可按域过滤（缺省=全部域含全局账号）；租户用户由后端强制本域
      tenantKey: isGlobal.value && query.tenantKey ? query.tenantKey : undefined
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
  query.roleCode = ''
  query.tenantKey = ''
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
  editing.value = false
  Object.assign(form, { id: null, username: '', password: '', realName: '', roleCode: 'OPERATOR', status: 1, tenantKey: isGlobal.value ? '' : userTenant.value })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, username: row.username, password: '', realName: row.realName, roleCode: row.roleCode, status: row.status })
  dialogVisible.value = true
}

function validPassword(pwd) {
  return pwd.length >= 8 && /[A-Za-z]/.test(pwd) && /\d/.test(pwd)
}

async function onSave() {
  if (!form.username.trim()) {
    ElMessage.warning('请填写账号')
    return
  }
  if (!editing.value && !form.password.trim()) {
    ElMessage.warning('请填写初始口令（线下告知使用人后妥善保管）')
    return
  }
  if (form.password.trim() && !validPassword(form.password.trim())) {
    ElMessage.warning('口令至少8位，且需同时包含字母和数字')
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      await updateUser(form.id, { realName: form.realName, roleCode: form.roleCode, status: String(form.status), password: form.password || undefined })
      ElMessage.success('账号已更新')
    } else {
      // tenantKey：空=全局账号；非空=租户账号（租户用户由后端强制本域）
      await createUser({
        username: form.username,
        password: form.password,
        realName: form.realName,
        roleCode: form.roleCode,
        tenantKey: form.tenantKey || undefined
      })
      // 不回显初始口令：口令仅创建者本人知道，线下交接
      ElMessage.success('账号已创建，口令已按线下流程告知管理员')
    }
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function toggleStatus(row) {
  const target = row.status === 1 ? 0 : 1
  await ElMessageBox.confirm(`确定${target === 0 ? '禁用' : '启用'}账号 ${row.username} 吗？`, '提示', { type: 'warning' })
  await updateUser(row.id, { status: String(target) })
  ElMessage.success('操作成功')
  load()
}

async function loadRoles() {
  try {
    const res = await listRoles()
    const list = Array.isArray(res.data) ? res.data : []
    if (list.length) roleOptions.value = list
  } catch (e) {
    // 角色接口失败时沿用兜底清单，不阻断账号管理
  }
}

onMounted(async () => {
  await loadRoles()
  load()
  tenantNames.value = await tenantNameMap()
  if (isGlobal.value) {
    tenantOptions.value = await listTenantsCached()
  }
})
</script>

<style scoped>
.mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}
.tenant-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
