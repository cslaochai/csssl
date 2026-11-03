<template>
  <div class="wf-node" :class="['wf-node-' + type, { selected }]">
    <div class="wf-node-title">{{ data.label }}</div>
    <div v-if="data.permission" class="wf-node-perm">{{ data.permission }}</div>
    <div v-if="data.nodeType === 1" class="wf-node-tag" :class="tagClass">{{ tagText }}</div>
    <Handle v-if="data.nodeType !== 0" type="target" :position="Position.Left" />
    <Handle v-if="data.nodeType !== 2" type="source" :position="Position.Right" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps({
  id: { type: String, default: '' },
  type: { type: String, default: 'middle' },
  data: { type: Object, default: () => ({}) },
  selected: { type: Boolean, default: false }
})

const tagText = computed(() => {
  const r = props.data.ratio
  if (!r || r === '0.000') return '或签'
  if (r === '100.000') return '会签'
  return `票签 ${r}%`
})

const tagClass = computed(() => {
  const r = props.data.ratio
  if (!r || r === '0.000') return 'tag-or'
  if (r === '100.000') return 'tag-all'
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

.wf-node-title { font-size: 14px; font-weight: 600; color: #1e293b }
.wf-node-start .wf-node-title { color: #065f46 }
.wf-node-end .wf-node-title { color: #374151 }

.wf-node-perm {
  font-size: 11px;
  color: #64748b;
  margin-top: 4px;
  font-family: monospace;
}
.wf-node-tag {
  display: inline-block;
  margin-top: 6px;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
}
.tag-or { background: #dbeafe; color: #1d4ed8 }
.tag-all { background: #fee2e2; color: #b91c1c }
.tag-vote { background: #fef3c7; color: #a16207 }

:deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  background: #3b82f6;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #3b82f6;
}
.wf-node-start :deep(.vue-flow__handle) { background: #10b981; box-shadow: 0 0 0 1px #10b981 }
.wf-node-end :deep(.vue-flow__handle) { background: #6b7280; box-shadow: 0 0 0 1px #6b7280 }
</style>
