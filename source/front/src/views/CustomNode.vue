<template>
  <div class="wf-node" :class="['wf-node-' + nodeType, { selected, 'wf-node-circle': nodeType !== 1 }]">
    <div class="wf-node-title">{{ data.label || '节点' }}</div>
    <div v-if="nodeType === 1 && data.permission" class="wf-node-perm">{{ data.permission }}</div>
    <div v-if="nodeType === 1" class="wf-node-tag" :class="tagClass">{{ tagText }}</div>
    <Handle v-if="nodeType !== 0" type="target" :position="Position.Left" />
    <Handle v-if="nodeType !== 2" type="source" :position="Position.Right" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Handle, Position, useNode } from '@vue-flow/core'

// Vue Flow 会把 NodeProps 中所有字段 (id/type/data/selected/dragging/connectable/zIndex 等)
// 作为 props 传入；不声明则作为 fallthrough attributes 挂到根元素上，
// 拖拽时 Vue 响应式代理与 DOM 属性冲突 -> Unhandled error during native event handler
defineProps({
  id: { type: [String, Number], default: '' },
  type: { type: String, default: 'default' },
  selected: { type: Boolean, default: false },
  dragging: { type: Boolean, default: false },
  connectable: { type: Boolean, default: true },
  draggable: { type: Boolean, default: true },
  resizing: { type: Boolean, default: false },
  zIndex: { type: Number, default: 0 },
  data: { type: Object, default: () => ({}) },
  dimensions: { type: Object, default: () => ({ width: 0, height: 0 }) },
  handleBounds: { type: String, default: '0' },
  sourcePosition: { type: String, default: 'right' },
  targetPosition: { type: String, default: 'left' },
  parentNodeId: { type: String, default: null },
  z: { type: Number, default: 0 },
  dragHandle: { type: String, default: null }
})

const { node } = useNode()

const nodeType = computed(() => Number(node.value?.data?.nodeType ?? 1))
const ratio = computed(() => node.value?.data?.ratio ?? '0.000')

const tagText = computed(() => {
  if (!ratio.value || ratio.value === '0.000') return '或签'
  if (ratio.value === '100.000') return '会签'
  return `票签 ${ratio.value}%`
})
const tagClass = computed(() => {
  if (!ratio.value || ratio.value === '0.000') return 'tag-or'
  if (ratio.value === '100.000') return 'tag-all'
  return 'tag-vote'
})
</script>

<style scoped>
.wf-node {
  background: #fff;
  border: 2px solid #3b82f6;
  border-radius: 10px;
  padding: 10px 14px;
  min-width: 140px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, .08);
  transition: box-shadow .15s;
  cursor: grab;
}
.wf-node:active { cursor: grabbing }
.wf-node:hover { box-shadow: 0 4px 12px rgba(59, 130, 246, .25) }
.wf-node.selected { border-color: #f59e0b; box-shadow: 0 0 0 3px rgba(245, 158, 11, .2) }
.wf-node-start { border-color: #10b981; background: #ecfdf5 }
.wf-node-end { border-color: #6b7280; background: #f3f4f6 }
/* 开始/结束节点渲染为正圆 */
.wf-node-circle {
  width: 76px;
  height: 76px;
  min-width: 0;
  padding: 6px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
}
.wf-node-circle .wf-node-title {
  font-size: 12px;
  line-height: 1.3;
  overflow: hidden;
  word-break: break-all;
}
.wf-node-title { font-size: 14px; font-weight: 600; color: #1e293b }
.wf-node-start .wf-node-title { color: #065f46 }
.wf-node-end .wf-node-title { color: #374151 }
.wf-node-perm { font-size: 11px; color: #64748b; margin-top: 4px; font-family: monospace }
.wf-node-tag { display: inline-block; margin-top: 6px; font-size: 10px; padding: 1px 6px; border-radius: 4px }
.tag-or { background: #dbeafe; color: #1d4ed8 }
.tag-all { background: #fee2e2; color: #b91c1c }
.tag-vote { background: #fef3c7; color: #a16207 }
:deep(.vue-flow__handle) { width: 10px; height: 10px; background: #3b82f6; border: 2px solid #fff; box-shadow: 0 0 0 1px #3b82f6 }
.wf-node-start :deep(.vue-flow__handle) { background: #10b981; box-shadow: 0 0 0 1px #10b981 }
.wf-node-end :deep(.vue-flow__handle) { background: #6b7280; box-shadow: 0 0 0 1px #6b7280 }
</style>
