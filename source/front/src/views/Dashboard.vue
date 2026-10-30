<template>
  <div class="dashboard">
    <!-- 运营指标：待办优先，点击进对应列表 -->
    <div class="stat-grid">
      <button
        v-for="card in statCards"
        :key="card.key"
        type="button"
        class="stat-card"
        :class="`tone-${card.tone}`"
        @click="$router.push(card.to)"
      >
        <div class="stat-head">
          <el-icon><component :is="card.icon" /></el-icon>
          <span>{{ card.label }}</span>
        </div>
        <div class="stat-num">{{ card.value }}</div>
        <div class="stat-hint">{{ card.hint }}</div>
      </button>
    </div>

    <div class="panel-row">
      <!-- 根CA：本租户信任根，先看是否就绪 -->
      <div class="page-card ca-card">
        <div class="section-title">
          根CA
          <el-tag :type="root.initialized ? 'success' : 'danger'" size="small" effect="light">
            {{ root.initialized ? '已就绪' : '未就绪' }}
          </el-tag>
          <router-link class="section-link" to="/root-ca">管理根CA</router-link>
        </div>
        <template v-if="root.initialized">
          <div class="ca-line">
            <span class="ca-label">根证书</span>
            <span class="ca-value">{{ root.cn }}</span>
          </div>
          <div class="ca-line">
            <span class="ca-label">算法</span>
            <span class="ca-value">{{ root.algorithm }}</span>
          </div>
          <div class="ca-line">
            <span class="ca-label">有效期至</span>
            <span class="ca-value mono">{{ fmtDate(root.validTo) }}</span>
          </div>
          <div class="ca-line">
            <span class="ca-label">状态</span>
            <span class="ca-value">{{ statusText(root.status) }}</span>
          </div>
        </template>
        <el-alert v-else :title="root.message || '根CA未初始化'" type="warning" :closable="false" show-icon />
      </div>

      <!-- 常用操作 -->
      <div class="page-card quick-card">
        <div class="section-title">常用操作</div>
        <div class="quick-actions">
          <button type="button" class="quick-item" @click="$router.push('/applications')">
            <el-icon><EditPen /></el-icon>
            <span>提交申请</span>
            <small>录入租户/个人主体与公钥</small>
          </button>
          <button type="button" class="quick-item" @click="$router.push('/certs')">
            <el-icon><DocumentAdd /></el-icon>
            <span>签发证书</span>
            <small>查看证书台账与签发记录</small>
          </button>
          <button type="button" class="quick-item" @click="$router.push('/crl')">
            <el-icon><CircleClose /></el-icon>
            <span>查看吊销列表</span>
            <small>CRL 与吊销记录</small>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getOverview, pageApplications } from '@/api/system'

const overview = ref({})
const root = ref({})
const pending = ref(0)

const STATUS_TEXT = {
  VALID: '有效',
  EXPIRED: '已过期',
  REVOKED: '已吊销',
  SUSPENDED: '已冻结'
}

function statusText(s) {
  return STATUS_TEXT[s] || s || '—'
}

function fmtDate(v) {
  return v ? String(v).replace('T', ' ').slice(0, 10) : '—'
}

const statCards = computed(() => [
  {
    key: 'pending',
    label: '待审核申请',
    value: pending.value,
    hint: '审核通过后方可签发',
    tone: 'warning',
    icon: 'EditPen',
    to: '/applications'
  },
  {
    key: 'expiring',
    label: '30天内到期',
    value: overview.value.expiring30d ?? 0,
    hint: '需尽快办理续期',
    tone: 'danger',
    icon: 'Clock',
    to: '/certs'
  },
  {
    key: 'revoked',
    label: '已吊销证书',
    value: overview.value.revoked ?? 0,
    hint: '查看吊销记录与 CRL',
    tone: 'muted',
    icon: 'CircleClose',
    to: '/crl'
  },
  {
    key: 'total',
    label: '证书总量',
    value: overview.value.total ?? 0,
    hint: `有效 ${overview.value.valid ?? 0} 张`,
    tone: 'primary',
    icon: 'Document',
    to: '/certs'
  }
])

async function load() {
  try {
    const [ov, apps] = await Promise.all([
      getOverview(),
      pageApplications({ status: 'PENDING', pageNo: 1, pageSize: 1 })
    ])
    overview.value = ov.data || {}
    root.value = ov.data?.root || {}
    pending.value = apps.data?.total ?? 0
  } catch (e) {
    // 错误提示已由拦截器统一处理
  }
}

onMounted(load)
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card {
  display: block;
  width: 100%;
  text-align: left;
  background: #fff;
  border: 1px solid var(--his-card-border);
  border-left: 4px solid var(--tone, var(--his-primary));
  border-radius: var(--his-radius);
  box-shadow: var(--his-shadow-sm);
  padding: 16px 20px;
  font-family: inherit;
  cursor: pointer;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.stat-card:hover {
  box-shadow: var(--his-shadow-md);
  transform: translateY(-1px);
}

.tone-warning {
  --tone: #e6a23c;
}

.tone-danger {
  --tone: #f56c6c;
}

.tone-muted {
  --tone: #94a3b8;
}

.tone-primary {
  --tone: var(--his-primary);
}

.stat-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #5b7893;
}

.stat-head .el-icon {
  color: var(--tone, var(--his-primary));
  font-size: 15px;
}

.stat-num {
  margin-top: 8px;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--his-title);
  font-variant-numeric: tabular-nums;
}

.stat-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #94a3b8;
}

.panel-row {
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  align-items: start;
  gap: 16px;
}

.panel-row .page-card {
  margin-bottom: 0;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title);
  margin-bottom: 14px;
}

.section-link {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
  color: var(--his-primary);
  text-decoration: none;
}

.section-link:hover {
  text-decoration: underline;
}

.ca-line {
  display: flex;
  gap: 12px;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed #eef3f9;
}

.ca-line:last-child {
  border-bottom: none;
}

.ca-label {
  flex-shrink: 0;
  width: 68px;
  color: #8496a8;
}

.ca-value {
  color: #33546e;
  word-break: break-all;
}

.mono {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}

.quick-actions {
  display: grid;
  gap: 10px;
}

.quick-item {
  display: grid;
  grid-template-columns: 20px 1fr;
  grid-template-rows: auto auto;
  column-gap: 12px;
  align-items: center;
  padding: 12px 14px;
  text-align: left;
  background: #f8fbfe;
  border: 1px solid #e9f1f9;
  border-radius: 8px;
  font-family: inherit;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.quick-item:hover {
  background: #eff7fe;
  border-color: #cfe3f5;
}

.quick-item .el-icon {
  grid-row: 1 / span 2;
  color: var(--his-primary);
  font-size: 17px;
}

.quick-item span {
  font-size: 13px;
  font-weight: 600;
  color: var(--his-title);
}

.quick-item small {
  font-size: 12px;
  color: #94a3b8;
}

@media (max-width: 1100px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .panel-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
