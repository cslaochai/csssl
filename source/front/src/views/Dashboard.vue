<template>
  <div>
    <!-- 根CA状态 -->
    <div class="page-card">
      <div class="section-title">
        根CA状态
        <el-tag :type="root.initialized ? 'success' : 'danger'" size="small" style="margin-left: 8px">
          {{ root.initialized ? '已初始化' : '未就绪' }}
        </el-tag>
      </div>
      <el-descriptions :column="3" border v-if="root.initialized">
        <el-descriptions-item label="证书CN">{{ root.cn }}</el-descriptions-item>
        <el-descriptions-item label="算法">
          <el-tag size="small">{{ root.algorithm }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="序列号">{{ root.serialHex }}</el-descriptions-item>
        <el-descriptions-item label="生效时间">{{ root.validFrom }}</el-descriptions-item>
        <el-descriptions-item label="过期时间">{{ root.validTo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ root.status }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-else :title="root.message || '根CA未初始化'" type="warning" :closable="false" />
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in statCards" :key="card.key">
        <div class="stat-card">
          <div class="stat-num" :style="{ color: card.color }">{{ card.value }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 快捷操作 -->
    <div class="page-card">
      <div class="section-title">快捷操作</div>
      <div class="quick-actions">
        <el-button type="primary" @click="$router.push('/certs')">签发证书</el-button>
        <el-button @click="$router.push('/root-ca')">管理根CA</el-button>
        <el-button @click="$router.push('/crl')">查看吊销列表</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getOverview } from '@/api/system'

const overview = ref({})
const root = ref({})

const statCards = computed(() => [
  { key: 'total', label: '证书总量', value: overview.value.total ?? 0, color: '#1d2a4a' },
  { key: 'valid', label: '有效', value: overview.value.valid ?? 0, color: '#67c23a' },
  { key: 'revoked', label: '已吊销', value: overview.value.revoked ?? 0, color: '#f56c6c' },
  { key: 'expiring30d', label: '30天内到期', value: overview.value.expiring30d ?? 0, color: '#e6a23c' }
])

async function load() {
  try {
    const res = await getOverview()
    overview.value = res.data
    root.value = res.data.root || {}
  } catch (e) {
    // 错误提示已由拦截器统一处理
  }
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d2a4a;
  margin-bottom: 14px;
  display: flex;
  align-items: center;
}
.stat-card {
  background: #fff;
  border-radius: 6px;
  padding: 20px;
  text-align: center;
  margin-bottom: 16px;
}
.stat-num {
  font-size: 30px;
  font-weight: 700;
}
.stat-label {
  margin-top: 6px;
  color: #909399;
  font-size: 13px;
}
.quick-actions {
  display: flex;
  gap: 12px;
}
</style>
