<template>
  <div v-if="diagram && diagram.steps && diagram.steps.length" class="wf-diagram">
    <div class="wf-head">
      <span class="wf-name">{{ diagram.name }}</span>
      <span v-if="diagram.nextApproverLabel" class="wf-next">下一步：{{ diagram.nextApproverLabel }}</span>
    </div>
    <div class="wf-steps">
      <template v-for="(s, i) in diagram.steps" :key="s.code">
        <div class="wf-node" :class="stateClass(s)">
          <div class="wf-dot">
            <el-icon v-if="s.state === 'DONE'"><Check /></el-icon>
            <el-icon v-else-if="s.state === 'REJECTED'"><Close /></el-icon>
            <el-icon v-else-if="s.state === 'CANCELLED'"><Close /></el-icon>
            <el-icon v-else-if="s.state === 'SKIPPED'"><Minus /></el-icon>
            <span v-else>{{ i + 1 }}</span>
          </div>
          <div class="wf-label">{{ s.name }}</div>
          <div class="wf-actor">{{ s.actorLabel }}</div>
        </div>
        <div v-if="i < diagram.steps.length - 1" class="wf-line" :class="lineClass(s, i)"></div>
      </template>
    </div>
  </div>
  <div v-else class="wf-empty">暂无流程信息</div>
</template>

<script setup>
import { Check, Close, Minus } from '@element-plus/icons-vue'

const props = defineProps({
  diagram: { type: Object, default: null }
})

function stateClass(s) {
  return {
    'is-done': s.state === 'DONE',
    'is-current': s.state === 'CURRENT',
    'is-pending': s.state === 'PENDING',
    'is-rejected': s.state === 'REJECTED',
    'is-cancelled': s.state === 'CANCELLED',
    'is-skipped': s.state === 'SKIPPED',
    'is-terminal': s.terminal
  }
}

// 连接线：已走过（DONE）节点之间的线为绿色；SKIPPED 段用虚线灰；REJECTED/CANCELLED 终止之后的线也不再用绿色
function lineClass(s, i) {
  if (!props.diagram) return {}
  const steps = props.diagram.steps
  const next = steps[i + 1]
  // 当前段（从 s 到 next）两端都 DONE → 绿线
  if (s.state === 'DONE' && next && next.state === 'DONE') return { done: true }
  // 任一端是 SKIPPED → 虚线
  if (s.state === 'SKIPPED' || (next && next.state === 'SKIPPED')) return { skipped: true }
  // 走到 REJECTED/CANCELLED 就终止了，这段用虚线
  if ((s.state === 'DONE' || s.state === 'CURRENT') && next && (next.state === 'REJECTED' || next.state === 'CANCELLED')) return { skipped: true }
  return {}
}
</script>

<style scoped>
.wf-diagram {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 14px 16px;
  background: #fafafa;
}
.wf-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 8px;
}
.wf-name {
  font-weight: 600;
  color: #303133;
  font-size: 14px;
}
.wf-next {
  font-size: 13px;
  color: #409eff;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 4px;
  padding: 2px 10px;
}
.wf-steps {
  display: flex;
  align-items: flex-start;
}
.wf-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 92px;
  flex: 0 0 auto;
}
.wf-dot {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  border: 2px solid #c0c4cc;
  background: #fff;
  color: #909399;
}
.wf-node.is-current .wf-dot {
  border-color: #409eff;
  background: #409eff;
  color: #fff;
  box-shadow: 0 0 0 4px #ecf5ff;
}
.wf-node.is-done .wf-dot {
  border-color: #67c23a;
  background: #67c23a;
  color: #fff;
}
/* 驳回：红色 × */
.wf-node.is-rejected .wf-dot {
  border-color: #f56c6c;
  background: #f56c6c;
  color: #fff;
}
.wf-node.is-rejected .wf-label {
  color: #f56c6c;
}
/* 撤销：灰色 × */
.wf-node.is-cancelled .wf-dot {
  border-color: #909399;
  background: #909399;
  color: #fff;
}
.wf-node.is-cancelled .wf-label {
  color: #909399;
}
/* 跳过：浅色边框 + 虚线 icon */
.wf-node.is-skipped .wf-dot {
  border-color: #dcdfe6;
  background: #f5f7fa;
  color: #c0c4cc;
}
.wf-node.is-skipped .wf-label,
.wf-node.is-skipped .wf-actor {
  color: #c0c4cc;
  text-decoration: line-through;
  text-decoration-color: #e4e7ed;
}
.wf-label {
  margin-top: 6px;
  font-size: 13px;
  color: #303133;
  text-align: center;
  line-height: 1.3;
}
.wf-node.is-pending .wf-label {
  color: #909399;
}
.wf-actor {
  margin-top: 2px;
  font-size: 11px;
  color: #909399;
  text-align: center;
  line-height: 1.3;
}
.wf-line {
  flex: 1 1 auto;
  height: 2px;
  background: #dcdfe6;
  margin-top: 14px;
  min-width: 16px;
}
.wf-line.done {
  background: #67c23a;
}
.wf-line.skipped {
  background: #e4e7ed;
  background-image: linear-gradient(90deg, #c0c4cc 50%, transparent 50%);
  background-size: 8px 2px;
  background-repeat: repeat-x;
}
.wf-empty {
  color: #909399;
  font-size: 13px;
  padding: 8px 0;
}
</style>
