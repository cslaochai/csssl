<template>
  <div class="wf-mgmt">
    <div class="wf-mgmt-bar">
      <el-input
          v-model="keyword"
          placeholder="按流程名称 / 编码搜索"
          size="default"
          clearable
          style="width:260px"
          @keyup.enter="refresh"
      >
        <template #prefix>
          <el-icon>
            <Search/>
          </el-icon>
        </template>
      </el-input>
      <el-button style="margin-left:8px" @click="refresh">搜索</el-button>
      <el-button style="margin-left:8px" @click="keyword='';refresh">重置</el-button>

      <div style="margin-left:auto">
        <el-button type="primary" @click="create">
          <el-icon style="margin-right:4px">
            <Plus/>
          </el-icon>
          新建流程
        </el-button>
      </div>
    </div>

    <el-table :data="pagedList" border stripe style="width:100%" v-loading="loading">
      <el-table-column prop="flowName" label="流程名称" min-width="180"/>
      <el-table-column prop="flowCode" label="流程编码" min-width="180">
        <template #default="{ row }"><code>{{ row.flowCode }}</code></template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="140"/>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.isPublish === 1" type="success">已发布</el-tag>
          <el-tag v-else type="warning">草稿</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" align="center"/>
      <el-table-column prop="updateTime" label="创建时间" min-width="150">
        <template #default="{ row }">{{ row.createTime }}</template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" min-width="150">
        <template #default="{ row }">{{ row.updateTime }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right" align="center">
        <template #default="{ row }">
          <template v-if="canEdit">
          <el-button size="small" link type="primary" @click="edit(row)">编辑</el-button>
          <el-button
              v-if="row.isPublish === 1"
              size="small"
              link
              type="warning"
              :loading="unpublishingId === row.id"
              @click="togglePublish(row, false)"
          >撤销发布
          </el-button>
          <el-button
              v-else
              size="small"
              link
              type="success"
              :loading="publishingId === row.id"
              @click="togglePublish(row, true)"
          >发布
          </el-button>
          <el-button
              size="small"
              link
              type="danger"
              :loading="deletingId === row.id"
              @click="remove(row)"
          >删除
          </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="wf-mgmt-footer">
      <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="filtered.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
      />
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {deleteDef, listDefs, publishDef, unpublishDef} from '@/api/workflow'
import {hasPerm} from '@/lib/perm'

const router = useRouter()

const loading = ref(false)
const canEdit = computed(() => hasPerm('workflow:edit'))
const keyword = ref('')
const defs = ref([])
const page = ref(1)
const pageSize = ref(10)
const publishingId = ref(null)
const unpublishingId = ref(null)
const deletingId = ref(null)

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return defs.value
  return defs.value.filter(d =>
      (d.flowName || '').toLowerCase().includes(kw) ||
      (d.flowCode || '').toLowerCase().includes(kw)
  )
})
const pagedList = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

async function refresh() {
  loading.value = true
  try {
    const res = await listDefs()
    defs.value = Array.isArray(res?.data) ? res.data : []
  } finally {
    loading.value = false
  }
}

function create() {
  router.push({name: 'workflow-designer'})
}

function edit(row) {
  router.push({name: 'workflow-designer', params: {id: row.id}})
}

async function togglePublish(row, wantPublish) {
  try {
    await ElMessageBox.confirm(
        wantPublish
            ? `确定发布流程「${row.flowName}」？发布后新发起的单据立即使用此版本。`
            : `确定撤销发布流程「${row.flowName}」？撤销后新发起的单据将无法使用此流程。`,
        wantPublish ? '发布确认' : '撤销发布确认',
        {type: 'warning'}
    )
  } catch {
    return
  }
  const id = row.id
  try {
    if (wantPublish) {
      publishingId.value = id
      await publishDef(id)
      ElMessage.success('已发布')
    } else {
      unpublishingId.value = id
      await unpublishDef(id)
      ElMessage.success('已撤销发布')
    }
    await refresh()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    publishingId.value = null
    unpublishingId.value = null
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
        `确定删除流程「${row.flowName}」？将级联删除节点、流转与历史设计记录。`,
        '删除确认',
        {type: 'warning'}
    )
  } catch {
    return
  }
  deletingId.value = row.id
  try {
    await deleteDef(row.id)
    ElMessage.success('已删除')
    await refresh()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  } finally {
    deletingId.value = null
  }
}

onMounted(refresh)
</script>

<style scoped>
.wf-mgmt {
  padding: 12px 16px;
  background: #fff;
  min-height: calc(100vh - 110px);
}

.wf-mgmt-bar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.wf-mgmt-footer {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
