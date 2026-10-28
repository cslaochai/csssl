<template>
  <div>
    <!-- 查询卡：角色档案由 GM/T 0034 固化，本页只做档案维护与权限授权 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="角色">
            <el-input
              v-model="query.keyword"
              clearable
              placeholder="角色名称/编码"
              style="width: 200px"
              @keyup.enter="handleSearch"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px" @change="handleSearch">
              <el-option label="启用" :value="1" />
              <el-option label="停用" :value="0" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
        <div class="query-actions">
          <el-tooltip content="六类角色由 GM/T 0034 分权职责固化：编码不可改、角色不可增删" placement="top">
            <el-tag type="info" effect="plain">六类法定角色</el-tag>
          </el-tooltip>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="pageRows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="roleCode" label="角色编码" width="150" class-name="cell-mono" />
        <el-table-column prop="roleName" label="角色名称" width="150" />
        <el-table-column prop="roleDesc" label="职责说明" min-width="300" show-overflow-tooltip />
        <el-table-column label="账号数" width="90" align="center">
          <template #default="{ row }">
            <span class="cell-count">{{ row.userCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="权限授权" width="120" align="center">
          <template #default="{ row }">
            <el-tooltip
              :content="`已授权 ${row.permCount} 项 / 法定可授 ${row.maxPermCount} 项`"
              placement="top"
            >
              <el-tag size="small" :type="row.permCount === row.maxPermCount ? 'success' : 'warning'">
                {{ row.permCount }} / {{ row.maxPermCount }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="sortOrder" label="排序" width="70" align="center" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'role:update'" link type="primary" size="small" :icon="Edit" @click="openEdit(row)">
              编辑
            </el-button>
            <el-button v-perm="'role:perm'" link type="primary" size="small" :icon="Setting" @click="openPerms(row)">
              权限配置
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="filteredRows.length === 0 && !loading" class="empty-tip">暂无匹配的角色</div>
      <div ref="footerRef" class="list-footer">
        <el-pagination
          v-model:current-page="query.pageNo"
          v-model:page-size="query.pageSize"
          :page-sizes="PAGE_SIZES"
          :total="filteredRows.length"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <!-- 编辑角色档案：编码只读，名称/职责/排序/启停可维护 -->
    <el-dialog v-model="editVisible" title="编辑角色" width="540px" :close-on-click-modal="false">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="角色编码">
          <el-input v-model="editForm.roleCode" disabled />
          <div class="form-hint">编码由 GM/T 0034 分权职责固化，不可修改；角色不可增删。</div>
        </el-form-item>
        <el-form-item label="角色名称" required>
          <el-input v-model="editForm.roleName" maxlength="64" show-word-limit placeholder="页面展示名称" />
        </el-form-item>
        <el-form-item label="职责说明">
          <el-input
            v-model="editForm.roleDesc"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="对应 GM/T 0034 的分权职责"
          />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="editForm.sortOrder" :min="0" :max="999" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="editForm.status"
            :active-value="1"
            :inactive-value="0"
            active-text="启用"
            inactive-text="停用"
            :disabled="editForm.roleCode === 'ADMIN'"
          />
          <div class="form-hint">
            {{ editForm.roleCode === 'ADMIN'
              ? '系统管理员角色不可停用（避免无人可管理角色与账号）'
              : '停用后该角色账号立即不可登录，已登录会话下一次请求即失效' }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" :loading="editSubmitting" @click="onSaveEdit">保存</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 权限配置：菜单 + 按钮，越法定边界置灰不可勾选 -->
    <el-dialog
      v-model="permVisible"
      :title="`配置权限 - ${permRole ? permRole.roleName : ''}`"
      width="760px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="只能在该角色的法定职责边界内授权：置灰项超出 GM/T 0034 分权边界，不可勾选；保存后即时生效"
        style="margin-bottom: 12px"
      />
      <div class="perm-summary">
        <span class="perm-summary-item">已授权 <b>{{ checkedCount }}</b> 项</span>
        <span class="perm-summary-item">法定可授 {{ permCatalog.filter(i => i.allowed).length }} 项</span>
        <el-button link type="primary" size="small" @click="checkAllAllowed">勾选全部可授权项</el-button>
        <el-button link type="danger" size="small" @click="clearChecked">清空</el-button>
      </div>
      <div v-loading="permLoading" class="perm-tree-wrap">
        <el-tree
          ref="permTreeRef"
          :data="permTree"
          :props="{ label: 'label', children: 'children' }"
          node-key="key"
          show-checkbox
          default-expand-all
          check-strictly
          @check="onTreeCheck"
        >
          <template #default="{ data }">
            <span class="perm-node">
              <span class="perm-node-label">{{ data.label }}</span>
              <el-tag v-if="data.isGroup" size="small" type="info" effect="plain">分组</el-tag>
              <el-tag v-else-if="data.type === 'MENU'" size="small" type="success" effect="plain">菜单</el-tag>
              <el-tag v-else size="small" effect="plain">按钮</el-tag>
              <el-tag v-if="data.isGroup ? false : !data.allowed" size="small" type="danger" effect="plain">法定越界</el-tag>
              <span v-if="data.note" class="perm-node-note">{{ data.note }}</span>
            </span>
          </template>
        </el-tree>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="permVisible = false">取消</el-button>
          <el-button type="primary" :loading="permSubmitting" @click="onSavePerms">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit, Refresh, Search, Setting } from '@element-plus/icons-vue'
import { listRoles, getRolePerms, updateRole, saveRolePerms, getMyPerms } from '@/api/system'
import { DEFAULT_PAGE_SIZE, PAGE_SIZES } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'
import { savePerms, saveRoleName } from '@/lib/perm'

const loading = ref(false)
const rows = ref([])
const query = reactive({ keyword: '', status: '', pageNo: 1, pageSize: DEFAULT_PAGE_SIZE })

const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()

const filteredRows = computed(() => {
  const kw = query.keyword.trim().toLowerCase()
  const hasStatus = query.status === 0 || query.status === 1
  return rows.value.filter(r => {
    if (kw && !`${r.roleCode} ${r.roleName}`.toLowerCase().includes(kw)) return false
    if (hasStatus && r.status !== query.status) return false
    return true
  })
})
const pageRows = computed(() => {
  const start = (query.pageNo - 1) * query.pageSize
  return filteredRows.value.slice(start, start + query.pageSize)
})

// ===== 编辑角色档案 =====
const editVisible = ref(false)
const editSubmitting = ref(false)
const editForm = reactive({ roleCode: '', roleName: '', roleDesc: '', sortOrder: 0, status: 1 })

// ===== 权限配置 =====
const permVisible = ref(false)
const permLoading = ref(false)
const permSubmitting = ref(false)
const permRole = ref(null)
const permCatalog = ref([])
const permTree = ref([])
const permTreeRef = ref(null)
const checkedCount = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await listRoles()
    rows.value = res.data || []
    const maxPage = Math.max(1, Math.ceil(filteredRows.value.length / query.pageSize))
    if (query.pageNo > maxPage) query.pageNo = maxPage
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNo = 1
}

function handleReset() {
  query.keyword = ''
  query.status = ''
  query.pageNo = 1
}

function handleSizeChange(val) {
  query.pageSize = val
  query.pageNo = 1
}

function handleCurrentChange(val) {
  query.pageNo = val
}

function openEdit(row) {
  Object.assign(editForm, {
    roleCode: row.roleCode,
    roleName: row.roleName,
    roleDesc: row.roleDesc || '',
    sortOrder: row.sortOrder ?? 0,
    status: row.status
  })
  editVisible.value = true
}

async function onSaveEdit() {
  if (!editForm.roleName.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  editSubmitting.value = true
  try {
    await updateRole(editForm.roleCode, {
      roleName: editForm.roleName.trim(),
      roleDesc: editForm.roleDesc.trim(),
      sortOrder: String(editForm.sortOrder ?? 0),
      status: String(editForm.status)
    })
    ElMessage.success('角色已更新')
    editVisible.value = false
    await load()
    // 改的是当前登录人所属角色时，顶栏名称与权限缓存需要同步
    await refreshOwnPermsIfMine(editForm.roleCode)
  } finally {
    editSubmitting.value = false
  }
}

async function openPerms(row) {
  permRole.value = row
  permCatalog.value = []
  permTree.value = []
  checkedCount.value = 0
  permVisible.value = true
  permLoading.value = true
  try {
    const res = await getRolePerms(row.roleCode)
    const data = res.data || {}
    permCatalog.value = data.catalog || []
    permTree.value = buildTree(permCatalog.value)
    const configured = data.configured || []
    // 只回显合法且允许的叶子（越界项即使库中存在也不勾选）
    const allowedCodes = new Set(permCatalog.value.filter(i => i.allowed).map(i => i.code))
    const checked = configured.filter(code => allowedCodes.has(code))
    // 等弹框与树渲染完成后再回显勾选
    await nextTick()
    permTreeRef.value?.setCheckedKeys(checked)
    checkedCount.value = checked.length
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    permLoading.value = false
  }
}

function buildTree(catalog) {
  const groups = []
  const groupMap = new Map()
  for (const item of catalog) {
    if (!groupMap.has(item.group)) {
      const group = {
        key: `group:${item.group}`,
        label: item.group,
        isGroup: true,
        disabled: true,
        children: []
      }
      groupMap.set(item.group, group)
      groups.push(group)
    }
    groupMap.get(item.group).children.push({
      key: item.code,
      label: item.name,
      type: item.type,
      note: item.note,
      allowed: item.allowed,
      disabled: !item.allowed
    })
  }
  return groups
}

function collectChecked() {
  const keys = permTreeRef.value?.getCheckedKeys() || []
  return keys.filter(k => !String(k).startsWith('group:'))
}

function onTreeCheck() {
  checkedCount.value = collectChecked().length
}

function checkAllAllowed() {
  const codes = permCatalog.value.filter(i => i.allowed).map(i => i.code)
  permTreeRef.value?.setCheckedKeys(codes)
  checkedCount.value = codes.length
}

function clearChecked() {
  permTreeRef.value?.setCheckedKeys([])
  checkedCount.value = 0
}

async function onSavePerms() {
  if (!permRole.value) return
  const codes = collectChecked()
  permSubmitting.value = true
  try {
    await saveRolePerms(permRole.value.roleCode, codes)
    ElMessage.success('权限已保存，即时生效')
    permVisible.value = false
    await load()
    await refreshOwnPermsIfMine(permRole.value.roleCode)
  } finally {
    permSubmitting.value = false
  }
}

/** 变更的是当前登录人所属角色时，刷新本地权限缓存与角色名称（侧栏/按钮即时更新） */
async function refreshOwnPermsIfMine(roleCode) {
  if (localStorage.getItem('role') !== roleCode) return
  try {
    const res = await getMyPerms()
    savePerms(res.data?.perms || [])
    saveRoleName(res.data?.roleName || '')
    // 广播给 Layout：侧栏与顶栏角色名无刷新重建
    window.dispatchEvent(new Event('perms-updated'))
  } catch (e) {
    // 失败不阻断保存结果；下次进入页面（Layout）会自动刷新
  }
}

onMounted(load)
</script>

<style scoped>
.cell-count {
  font-variant-numeric: tabular-nums;
}

.form-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-top: 4px;
}

.perm-summary {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 0 10px;
  font-size: 13px;
  color: #606266;
}

.perm-summary-item b {
  color: var(--his-primary, #0b4f8a);
}

.perm-tree-wrap {
  max-height: 56vh;
  overflow-y: auto;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
}

.perm-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.perm-node-label {
  font-size: 13px;
  color: #303133;
}

.perm-node-note {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
