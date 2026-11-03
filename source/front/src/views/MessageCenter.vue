<template>
  <div class="todo-page">
    <!-- 待我审批：二级CA重建申请审批任务 -->
    <el-card v-if="myPending.length" class="todo-card" shadow="never">
      <div class="todo-head">
        <span class="todo-title">
          <el-icon><Stamp/></el-icon> 待我审批
          <el-tag size="small" type="warning" effect="light">{{ myPending.length }}</el-tag>
        </span>
        <span class="todo-sub">二级CA重建申请需你（密钥保管人）审批，审批时填入本人持有的根私钥份额（秘钥片）</span>
      </div>
      <div v-for="t in myPending" :key="t.id" class="todo-row">
        <div class="todo-main">
          <span class="todo-no cell-mono">{{ t.opNo }}</span>
          <span class="todo-company">{{ t.companyName || t.companyUscc }}</span>
          <span class="cell-sub">{{ t.requestedByName || '无' }} 发起 · {{ t.requestedAt }}</span>
        </div>
        <div class="todo-side">
          <span class="todo-progress">已审 {{ t.approvedCount }}/{{ t.threshold }}</span>
          <el-button size="small" type="primary" @click="goApprove(t)">审批</el-button>
        </div>
      </div>
    </el-card>

    <!-- 待我执行：我已发起且审批通过的重建申请，提交份额解封 + 重建 -->
    <el-card v-if="myExecutable.length" class="todo-card" shadow="never">
      <div class="todo-head">
        <span class="todo-title">
          <el-icon><Finished/></el-icon> 待我执行
          <el-tag size="small" type="success" effect="light">{{ myExecutable.length }}</el-tag>
        </span>
        <span class="todo-sub">以下申请已获 {{ thresholdHint }} 名保管人同意，可提交份额解封并重建二级CA</span>
      </div>
      <div v-for="t in myExecutable" :key="t.id" class="todo-row">
        <div class="todo-main">
          <span class="todo-no cell-mono">{{ t.opNo }}</span>
          <span class="todo-company">{{ t.companyName || t.companyUscc }}</span>
          <span class="cell-sub">事由：{{ t.reason }}</span>
        </div>
        <div class="todo-side">
          <span class="todo-progress todo-ok">已通过 {{ t.approvedCount }}/{{ t.threshold }}</span>
          <el-button size="small" type="warning" @click="goExecute(t)">提交份额解封 + 重建二级CA</el-button>
        </div>
      </div>
    </el-card>

    <!-- 我的消息 -->
    <el-card class="query-card" shadow="never">
      <div class="mc-toolbar">
        <div class="mc-title">我的消息</div>
        <div class="mc-actions">
          <el-switch v-model="unreadOnly" active-text="仅看未读" @change="handleSearch"/>
          <el-button :disabled="!hasUnread" type="primary" @click="markAllRead">全部标为已读</el-button>
        </div>
      </div>
      <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.readFlag === 0" size="small" type="danger">未读</el-tag>
            <el-tag v-else size="small" type="info">已读</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.type)" size="small">{{ typeText(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="160" prop="title"/>
        <el-table-column label="内容" min-width="300" prop="content" show-overflow-tooltip/>
        <el-table-column label="发送人" width="140">
          <template #default="{ row }">{{ row.senderRealName || '无' }}</template>
        </el-table-column>
        <el-table-column label="时间" prop="createdAt" width="170"/>
        <el-table-column fixed="right" label="操作" width="100">
          <template #default="{ row }">
            <el-button v-if="row.readFlag === 0" link size="small" type="primary" @click="markRead(row)">标为已读</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="list-footer">
        <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                       :page-sizes="PAGE_SIZES" :total="total" layout="total, sizes, prev, pager, next, jumper"
                       @size-change="handleSizeChange" @current-change="handleCurrentChange"/>
      </div>
    </el-card>

    <div v-if="!myPending.length && !myExecutable.length" class="empty-tip">暂无待办任务</div>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {Finished, Stamp} from '@element-plus/icons-vue'
import {
  markMessagesRead,
  myExecutableSubCaRebuild,
  pageMyMessages,
  pendingSubCaRebuild
} from '@/api/system'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'

const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const unreadOnly = ref(false)
const hasUnread = ref(false)
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE})

// 待办：待我审批 / 待我执行
const myPending = ref([])
const myExecutable = ref([])
const thresholdHint = ref(3)

const username = ref(localStorage.getItem('username') || '')

function typeText(t) {
  return {
    APP_URGE: '催办',
    APP_SUBMITTED: '新待审',
    APP_APPROVED: '已批准',
    APP_REJECTED: '已驳回',
    APP_ISSUED: '已签发',
    SUBCA_REBUILD: '二级CA重建'
  }[t] || t
}

function typeTag(t) {
  return {
    APP_URGE: 'warning',
    APP_SUBMITTED: 'primary',
    APP_APPROVED: 'success',
    APP_REJECTED: 'danger',
    APP_ISSUED: 'success',
    SUBCA_REBUILD: 'warning'
  }[t] || 'info'
}

function goApprove(t) {
  // 跳审批中心并带入单号，由审批中心自动打开审批弹框（预填本人份额）
  router.push({path: '/approval-center', query: {ticketId: t.id}})
}

function goExecute(t) {
  // 跳审批中心并带入单号 + action=execute，由审批中心打开"提交份额解封+重建"弹框
  router.push({path: '/approval-center', query: {ticketId: t.id, action: 'execute'}})
}

async function loadMessages() {
  loading.value = true
  try {
    const res = await pageMyMessages({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      readFlag: unreadOnly.value ? 0 : undefined
    })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
    hasUnread.value = rows.value.some(r => r.readFlag === 0)
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function loadTodos() {
  try {
    const [pending, exec] = await Promise.all([pendingSubCaRebuild(), myExecutableSubCaRebuild()])
    myPending.value = pending.data || []
    myExecutable.value = exec.data || []
    if (myPending.value[0]) thresholdHint.value = myPending.value[0].threshold || 3
  } catch (e) {
    // 非保管人/无待办时接口可能为空，忽略
  }
}

function handleSearch() {
  query.pageNo = 1
  loadMessages()
}

function handleSizeChange(v) {
  query.pageSize = v
  query.pageNo = 1
  loadMessages()
}

function handleCurrentChange(v) {
  query.pageNo = v
  loadMessages()
}

async function markRead(row) {
  try {
    await markMessagesRead([row.id])
    row.readFlag = 1
    ElMessage.success('已标为已读')
    refreshUnread()
  } catch (e) {
  }
}

async function markAllRead() {
  try {
    await markMessagesRead([])
    rows.value.forEach(r => r.readFlag = 1)
    hasUnread.value = false
    ElMessage.success('已全部标为已读')
    refreshUnread()
  } catch (e) {
  }
}

function refreshUnread() {
  window.dispatchEvent(new Event('messages-updated'))
}

onMounted(() => {
  loadMessages()
  loadTodos()
})
</script>

<style scoped>
.todo-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.todo-card {
  border-left: 4px solid var(--his-primary, #0a3d6b);
}

.todo-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.todo-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.todo-sub {
  font-size: 12px;
  color: #909399;
}

.todo-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid #eef3f9;
  border-radius: 8px;
  margin-bottom: 8px;
  background: #f8fbfe;
}

.todo-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.todo-no {
  font-weight: 600;
  color: #33546e;
}

.todo-company {
  color: #303133;
  font-size: 13px;
}

.todo-side {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.todo-progress {
  font-size: 12px;
  color: #909399;
}

.todo-ok {
  color: #67c23a;
  font-weight: 600;
}

.mc-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.mc-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.mc-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cell-sub {
  font-size: 12px;
  color: #909399;
}

.list-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.empty-tip {
  color: #909399;
  font-size: 13px;
  padding: 16px 0;
  text-align: center;
}
</style>
