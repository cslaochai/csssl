<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="filters">
          <el-select v-model="query.roleCode" placeholder="角色" clearable style="width: 150px" @change="reload">
            <el-option v-for="r in roles" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
          <el-button type="primary" plain @click="reload">查询</el-button>
        </div>
        <div>
          <el-button type="primary" @click="openCreate">创建管理账号</el-button>
        </div>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="账号" min-width="130" />
        <el-table-column prop="realName" label="姓名" min-width="130" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="roleTag(row.roleCode)">{{ roleText(row.roleCode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最近登录" width="170" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="row.username === 'admin'" @click="openEdit(row)">编辑</el-button>
            <el-button
              link
              size="small"
              :type="row.status === 1 ? 'danger' : 'success'"
              :disabled="row.username === 'admin'"
              @click="toggleStatus(row)"
            >{{ row.status === 1 ? '禁用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 14px; justify-content: flex-end"
        @change="load"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑账号' : '创建管理账号'" width="480px" :close-on-click-modal="false">
      <el-form :model="form" label-width="100px">
        <el-form-item label="账号" required>
          <el-input v-model="form.username" :disabled="editing" placeholder="登录账号（创建后不可改）" />
        </el-form-item>
        <el-form-item :label="editing ? '重置口令(可选)' : '口令'" required>
          <el-input v-model="form.password" :placeholder="editing ? '留空则不修改口令' : '默认 123456'" show-password />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" placeholder="真实姓名/岗位" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roleCode" style="width: 100%">
            <el-option v-for="r in roles" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
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
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageUsers, createUser, updateUser } from '@/api/system'

const roles = [
  { value: 'ADMIN', label: '系统管理员' },
  { value: 'OPERATOR', label: 'CA操作员（录入/签发）' },
  { value: 'APPROVER', label: '审核员（审批申请）' },
  { value: 'AUDITOR', label: '审计员（只读）' }
]

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, roleCode: '' })
const dialogVisible = ref(false)
const editing = ref(false)
const submitting = ref(false)
const form = reactive({ id: null, username: '', password: '', realName: '', roleCode: 'OPERATOR', status: 1 })

function roleText(r) {
  return roles.find(i => i.value === r)?.label || r
}
function roleTag(r) {
  return { ADMIN: 'danger', OPERATOR: '', APPROVER: 'warning', AUDITOR: 'info' }[r] || ''
}

async function load() {
  loading.value = true
  try {
    const res = await pageUsers({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      roleCode: query.roleCode || undefined
    })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  query.pageNo = 1
  load()
}

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, username: '', password: '123456', realName: '', roleCode: 'OPERATOR', status: 1 })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, username: row.username, password: '', realName: row.realName, roleCode: row.roleCode, status: row.status })
  dialogVisible.value = true
}

async function onSave() {
  if (!form.username.trim()) {
    ElMessage.warning('请填写账号')
    return
  }
  if (!editing.value && !form.password.trim()) {
    ElMessage.warning('请填写口令')
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      await updateUser(form.id, { realName: form.realName, roleCode: form.roleCode, status: String(form.status), password: form.password || undefined })
      ElMessage.success('账号已更新')
    } else {
      await createUser({ username: form.username, password: form.password, realName: form.realName, roleCode: form.roleCode })
      ElMessage.success(`账号创建成功（初始口令：${form.password}）`)
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

onMounted(load)
</script>

<style scoped>
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
