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
            <span v-else>{{ i + 1 }}</span>
          </div>
          <div class="wf-label">{{ s.name }}</div>
          <div class="wf-actor">{{ s.actorLabel }}</div>
        </div>
        <div v-if="i < diagram.steps.length - 1" class="wf-line" :class="{ done: isLineDone(s) }"></div>
      </template>
    </div>
  </div>
  <div v-else class="wf-empty">暂无流程信息</div>
</template>

<script setup>
import { Check } from '@element-plus/icons-vue'

const props = defineProps({
  diagram: { type: Object, default: null }
})

function stateClass(s) {
  return {
    'is-done': s.state === 'DONE',
    'is-current': s.state === 'CURRENT',
    'is-pending': s.state === 'PENDING',
    'is-terminal': s.terminal
  }
}

// 连接线：当前步之前（含当前）的线视为已完成
function isLineDone(s) {
  if (!props.diagram) return false
  const steps = props.diagram.steps
  const idx = steps.findIndex(x => x.code === s.code)
  const curIdx = steps.findIndex(x => x.code === props.diagram.currentStepCode)
  if (curIdx < 0) return false
  return idx < curIdx
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
.wf-empty {
  color: #909399;
  font-size: 13px;
  padding: 8px 0;
}
</style>
