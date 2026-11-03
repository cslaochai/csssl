<template>
  <div class="todo-page">
    <!-- 概览卡：三组待办的数量 -->
    <el-card class="summary-card" shadow="never">
      <div class="summary-row">
        <div class="summary-item" :class="{ active: tab === 'pending' }" @click="tab = 'pending'">
          <div class="summary-num">{{ pending.length }}</div>
          <div class="summary-label">待我处理</div>
        </div>
        <div class="summary-item" :class="{ active: tab === 'initiated' }" @click="tab = 'initiated'">
          <div class="summary-num">{{ initiated.length }}</div>
          <div class="summary-label">我发起的</div>
        </div>
        <div class="summary-item" :class="{ active: tab === 'done' }" @click="tab = 'done'">
          <div class="summary-num">{{ done.length }}</div>
          <div class="summary-label">已办</div>
        </div>
      </div>
      <div class="summary-tip">
        四条审批流（RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建）统一收敛到一处；
        每单都标明「当前环节」与「当前该谁管」，点右侧按钮直达处理页。
      </div>
    </el-card>

    <!-- 待办列表 -->
    <el-card class="table-card" shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane name="pending">
          <template #label>
            <span class="tab-label">待我处理<el-badge v-if="pending.length" :value="pending.length" class="tab-badge"/></span>
          </template>
          <el-table v-loading="loading" :data="pending" stripe style="width: 100%">
            <el-table-column label="流程" min-width="200">
              <template #default="{ row }">
                <el-tag size="small" :type="bizTag(row.bizType)">{{ bizLabel(row.bizType) }}</el-tag>
                <div class="cell-sub">{{ row.bizNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="220" prop="title" show-overflow-tooltip/>
            <el-table-column label="当前环节" min-width="140" prop="stepName"/>
            <el-table-column label="当前处理人" min-width="140">
              <template #default="{ row }">{{ row.nextHandler || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="warning">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起" min-width="150">
              <template #default="{ row }">
                {{ row.requestedByName || '无' }}
                <div class="cell-sub">{{ row.requestedAt || '' }}</div>
              </template>
            </el-table-column>
            <el-table-column align="right" fixed="right" label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="go(row)">{{ row.actionLabel || '去处理' }}</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-tip">暂无待我处理的单据</div>
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="我发起的" name="initiated">
          <el-table v-loading="loading" :data="initiated" stripe style="width: 100%">
            <el-table-column label="流程" min-width="200">
              <template #default="{ row }">
                <el-tag size="small" :type="bizTag(row.bizType)">{{ bizLabel(row.bizType) }}</el-tag>
                <div class="cell-sub">{{ row.bizNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="220" prop="title" show-overflow-tooltip/>
            <el-table-column label="当前环节" min-width="140" prop="stepName"/>
            <el-table-column label="当前处理人" min-width="140">
              <template #default="{ row }">{{ row.nextHandler || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="primary">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起时间" prop="requestedAt" width="170"/>
            <el-table-column align="right" fixed="right" label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="go(row)">查看</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-tip">暂无我发起的单据</div>
            </template>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="已办" name="done">
          <el-table v-loading="loading" :data="done" stripe style="width: 100%">
            <el-table-column label="流程" min-width="200">
              <template #default="{ row }">
                <el-tag size="small" :type="bizTag(row.bizType)">{{ bizLabel(row.bizType) }}</el-tag>
                <div class="cell-sub">{{ row.bizNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="220" prop="title" show-overflow-tooltip/>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="success">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="发起" min-width="150">
              <template #default="{ row }">
                {{ row.requestedByName || '无' }}
                <div class="cell-sub">{{ row.requestedAt || '' }}</div>
              </template>
            </el-table-column>
            <el-table-column align="right" fixed="right" label="操作" width="110">
              <template #default="{ row }">
                <el-button link size="small" type="primary" @click="go(row)">查看</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-tip">暂无已办单据</div>
            </template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {getWorkflowTodoBoard} from '@/api/workflow'

const router = useRouter()

const loading = ref(false)
const tab = ref('pending')
const pending = ref([])
const initiated = ref([])
const done = ref([])

const BIZ_LABELS = {
  CERT_APPLICATION: 'RA 申请',
  CERT_OP: '证书操作单',
  COMPANY_OP: '公司变更单',
  SUB_CA_REBUILD: 'SUB_CA 重建'
}

const BIZ_TAGS = {
  CERT_APPLICATION: 'primary',
  CERT_OP: 'warning',
  COMPANY_OP: 'success',
  SUB_CA_REBUILD: 'danger'
}

function bizLabel(t) {
  return BIZ_LABELS[t] || t
}

function bizTag(t) {
  return BIZ_TAGS[t] || 'info'
}

/** 跳转到对应业务页并直达该单详情 */
function go(row) {
  if (!row?.route) return
  const [path, queryStr] = row.route.split('?')
  const query = {}
  if (queryStr) {
    queryStr.split('&').forEach(kv => {
      const [k, v] = kv.split('=')
      if (k) query[k] = v
    })
  }
  router.push({path, query})
}

async function load() {
  loading.value = true
  try {
    const res = await getWorkflowTodoBoard()
    const data = res.data || {}
    pending.value = data.pending || []
    initiated.value = data.initiated || []
    done.value = data.done || []
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.todo-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary-card {
  border-left: 4px solid var(--his-primary, #0a3d6b);
}

.summary-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.summary-item {
  flex: 1 1 160px;
  min-width: 140px;
  padding: 12px 16px;
  border: 1px solid #eef3f9;
  border-radius: 8px;
  background: #f8fbfe;
  cursor: pointer;
  transition: all 0.2s;
}

.summary-item:hover {
  border-color: #409eff;
}

.summary-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}

.summary-num {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}

.summary-item.active .summary-num {
  color: #409eff;
}

.summary-label {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}

.summary-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tab-badge {
  transform: translateY(-1px);
}
</style>
