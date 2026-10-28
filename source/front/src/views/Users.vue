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
          <el-form-item v-if="isGlobal" label="机构域">
            <el-select v-model="query.orgKey" placeholder="全部" clearable style="width: 170px" @change="handleSearch">
              <el-option label="default（全局/默认域）" value="default" />
              <el-option v-for="o in orgOptions" :key="o.orgKey" :label="o.orgName ? `${o.orgKey}（${o.orgName}）` : o.orgKey" :value="o.orgKey" />
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
        <el-table-column prop="id" label="ID" width="60" class-name="cell-mono" />
        <el-table-column prop="username" label="账号" min-width="130" class-name="cell-mono" />
        <el-table-column prop="realName" label="姓名" min-width="130" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="roleTag(row.roleCode)">{{ roleText(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="归属机构" width="130">
          <template #default="{ row }">
            <el-tag v-if="!row.orgKey" size="small" type="info">全局</el-tag>
            <span v-else class="mono">{{ row.orgKey }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最近登录" width="170" />
        <el-table-column align="right" label="操作" width="220" fixed="right">
          <template #default="{ row }">
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
        <el-form-item :label="editing ? '重置口令(可选)' : '口令'" required>
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
          <div class="org-tip">角色由角色配置页维护：停用角色不可分配，名称/职责以角色配置为准</div>
        </el-form-item>
        <el-form-item v-if="!editing" label="归属机构">
          <template v-if="isGlobal">
            <el-select v-model="form.orgKey" style="width: 100%">
              <el-option label="全局账号（跨机构，不限定域）" value="" />
              <el-option label="default（默认域）" value="default" />
              <el-option v-for="o in orgOptions" :key="o.orgKey" :label="o.orgName ? `${o.orgKey}（${o.orgName}）` : o.orgKey" :value="o.orgKey" />
            </el-select>
            <div class="org-tip">机构账号仅能操作本机构数据；全局账号由后端按角色+机构策略管控。</div>
          </template>
          <el-tag v-else size="small" type="warning">机构用户建号恒归本机构：{{ userOrg }}</el-tag>
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
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { pageUsers, createUser, updateUser, listOrgsCached, listRoles } from '@/api/system'
import { isGlobalUser, userOrgKey } from '@/lib/org'
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
const userOrg = ref(userOrgKey())
const orgOptions = ref([])

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, roleCode: '', orgKey: '' })

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()
const dialogVisible = ref(false)
const editing = ref(false)
const submitting = ref(false)
const form = reactive({ id: null, username: '', password: '', realName: '', roleCode: 'OPERATOR', status: 1, orgKey: '' })

function roleText(r) {
  return roleMap.value[r]?.roleName || r
}
function roleTag(r) {
  return { ADMIN: 'danger', OPERATOR: '', APPROVER: 'warning', AUDITOR: 'info', SECURITY_ADMIN: 'primary', KEY_ADMIN: 'warning' }[r] || ''
}

async function load() {
  loading.value = true
  try {
    const res = await pageUsers({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      roleCode: query.roleCode || undefined,
      // 全局用户可按域过滤（缺省=全部域含全局账号）；机构用户由后端强制本域
      orgKey: isGlobal.value && query.orgKey ? query.orgKey : undefined
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
  query.orgKey = ''
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
  Object.assign(form, { id: null, username: '', password: '', realName: '', roleCode: 'OPERATOR', status: 1, orgKey: isGlobal.value ? '' : userOrg.value })
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
      // orgKey：空=全局账号；非空=机构账号（机构用户由后端强制本域）
      await createUser({
        username: form.username,
        password: form.password,
        realName: form.realName,
        roleCode: form.roleCode,
        orgKey: form.orgKey || undefined
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
  if (isGlobal.value) {
    orgOptions.value = await listOrgsCached()
  }
})
</script>

<style scoped>
.mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}
.org-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
