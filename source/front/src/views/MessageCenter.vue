<template>
  <div>
    <el-card shadow="never">
      <div class="mc-toolbar">
        <div class="mc-title">消息中心</div>
        <div class="mc-actions">
          <el-switch v-model="unreadOnly" active-text="仅看未读" @change="handleSearch" />
          <el-button type="primary" :disabled="!hasUnread" @click="markAllRead">全部标为已读</el-button>
        </div>
      </div>
      <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.readFlag === 0" type="danger" size="small">未读</el-tag>
            <el-tag v-else type="info" size="small">已读</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.type)" size="small">{{ typeText(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
        <el-table-column label="发送人" width="140">
          <template #default="{ row }">{{ row.senderUsername || '系统' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.readFlag === 0" link type="primary" size="small" @click="markRead(row)">标为已读</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无消息</div>
      <div class="list-footer">
        <el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize"
                       :page-sizes="PAGE_SIZES" :total="total" layout="total, sizes, prev, pager, next, jumper"
                       @size-change="handleSizeChange" @current-change="handleCurrentChange" />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pageMyMessages, markMessagesRead } from '@/api/system'
import { DEFAULT_PAGE_SIZE, PAGE_SIZES } from '@/lib/pagination'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const unreadOnly = ref(false)
const hasUnread = ref(false)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE })

function typeText(t) {
  return { APP_URGE: '催办', APP_SUBMITTED: '新待审', APP_APPROVED: '已批准', APP_REJECTED: '已驳回', APP_ISSUED: '已签发' }[t] || t
}
function typeTag(t) {
  return { APP_URGE: 'warning', APP_SUBMITTED: 'primary', APP_APPROVED: 'success', APP_REJECTED: 'danger', APP_ISSUED: 'success' }[t] || 'info'
}

async function load() {
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
  } finally {
    loading.value = false
  }
}

function handleSearch() { query.pageNo = 1; load() }
function handleSizeChange(v) { query.pageSize = v; query.pageNo = 1; load() }
function handleCurrentChange(v) { query.pageNo = v; load() }

async function markRead(row) {
  try {
    await markMessagesRead([row.id])
    row.readFlag = 1
    ElMessage.success('已标为已读')
    refreshUnread()
  } catch (e) {}
}

async function markAllRead() {
  try {
    await markMessagesRead([])
    rows.value.forEach(r => r.readFlag = 1)
    hasUnread.value = false
    ElMessage.success('已全部标为已读')
    refreshUnread()
  } catch (e) {}
}

function refreshUnread() {
  // 通知顶栏红点立即刷新
  window.dispatchEvent(new Event('messages-updated'))
}

onMounted(load)
</script>

<style scoped>
.mc-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.mc-title { font-size: 16px; font-weight: 600; color: #303133; }
.mc-actions { display: flex; align-items: center; gap: 12px; }
.empty-tip { color: #909399; font-size: 13px; padding: 16px 0; text-align: center; }
.list-footer { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
