<template>
  <div class="wf-designer">
    <!-- 顶部工具栏 -->
    <div class="wf-toolbar">
      <el-select
        v-model="currentCode"
        placeholder="选择已有流程"
        size="small"
        style="width:220px"
        filterable
        clearable
        @change="loadDef"
      >
        <el-option v-for="d in defs" :key="d.id" :label="`${d.flowName}（${d.flowCode}）`" :value="d.flowCode" />
      </el-select>
      <el-button size="small" style="margin-left:8px" @click="newDef">＋ 新建</el-button>

      <el-divider direction="vertical" />

      <el-input v-model="def.flowName" placeholder="流程名称" style="width:170px" size="small" />
      <el-input
        v-model="def.flowCode"
        placeholder="流程编码(唯一)"
        style="width:170px;margin-left:8px"
        size="small"
        :disabled="!!def.id"
      />
      <el-input v-model="def.category" placeholder="分类" style="width:120px;margin-left:8px" size="small" />

      <el-tag v-if="def.isPublish === 1" type="success" style="margin-left:16px">已发布</el-tag>
      <el-tag v-else-if="def.id" type="warning" style="margin-left:16px">草稿</el-tag>
      <el-tag v-else type="info" style="margin-left:16px">新建</el-tag>

      <div style="margin-left:auto;display:flex;gap:8px">
        <el-button size="small" @click="saveDraft" :loading="saving">保存草稿</el-button>
        <el-button
          size="small"
          type="success"
          :disabled="!def.id || def.isPublish === 1"
          :loading="publishing"
          @click="publish"
        >发布</el-button>
        <el-button size="small" type="danger" :disabled="!def.id" @click="remove">删除</el-button>
      </div>
    </div>

    <div class="wf-body">
      <!-- 左侧节点面板 -->
      <div class="wf-panel wf-panel-left">
        <div class="panel-title">拖拽节点到画布</div>
        <div
          v-for="n in nodePalette"
          :key="n.type"
          class="palette-item"
          draggable="true"
          @dragstart="onDragStart($event, n)"
        >
          <span class="palette-icon">{{ n.icon }}</span>
          <span class="palette-label">{{ n.label }}</span>
        </div>

        <el-divider style="margin:12px 0" />
        <div class="panel-title">角色权限标识</div>
        <div v-for="r in rolePalette" :key="r.code" class="role-chip">
          <code>role:{{ r.code }}</code>
          <span class="role-name">· {{ r.name }}</span>
        </div>
      </div>

      <!-- 中间画布 -->
      <div class="wf-canvas" @drop="onDrop" @dragover.prevent>
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :default-edge-options="{ type: 'smoothstep' }"
          :connect-on-click="false"
          :delete-key-code="null"
          :min-zoom="0.2"
          :max-zoom="2"
          fit-view-on-init
          @node-click="onNodeClick"
          @pane-click="onPaneClick"
          @edge-click="onEdgeClick"
          @connect="onConnect"
        >
          <Background :gap="16" pattern-color="#e2e8f0" />
          <Controls position="bottom-left" />
          <MiniMap position="bottom-right" />

          <template #node-start="nodeProps">
            <CustomNode v-bind="nodeProps" />
          </template>
          <template #node-middle="nodeProps">
            <CustomNode v-bind="nodeProps" />
          </template>
          <template #node-end="nodeProps">
            <CustomNode v-bind="nodeProps" />
          </template>
        </VueFlow>

        <div v-if="!nodes.length" class="canvas-empty">
          从左侧拖入节点开始设计流程
        </div>
      </div>

      <!-- 右侧属性面板 -->
      <div class="wf-panel wf-panel-right">
        <template v-if="selectedNode">
          <div class="panel-title">节点属性（{{ selectedNode.data.label }}）</div>
          <div class="form-row">
            <label>节点名称</label>
            <el-input v-model="selectedNode.data.label" size="small" />
          </div>
          <div class="form-row">
            <label>权限标识</label>
            <el-input
              v-model="selectedNode.data.permission"
              size="small"
              placeholder="role:APPROVER@@role:RA_ADMIN"
            />
            <div class="form-hint">多个用 <code>@@</code> 分隔，如 role:APPROVER@@role:RA_ADMIN</div>
          </div>
          <div class="form-row">
            <label>签核方式</label>
            <el-select v-model="selectedNode.data.ratio" size="small" style="width:100%">
              <el-option label="或签（任一通过）" value="0.000" />
              <el-option label="会签（全部通过）" value="100.000" />
              <el-option label="票签 60%（3-of-5）" value="60.000" />
              <el-option label="票签 80%" value="80.000" />
            </el-select>
          </div>
          <el-button size="small" type="danger" text @click="deleteSelectedNode">删除此节点</el-button>
        </template>
        <template v-else>
          <div class="panel-title">操作提示</div>
          <ul class="tips">
            <li>左侧节点拖到画布即可创建</li>
            <li>直接拖动节点可调整位置</li>
            <li>从节点右侧圆点拖到下一节点左侧圆点建立流转</li>
            <li>点击节点在右侧改名称/权限/签核方式</li>
            <li>点击连线可改名称或删除</li>
          </ul>
        </template>
      </div>
    </div>

    <!-- 连线编辑弹窗 -->
    <el-dialog v-model="edgeDialogVisible" title="流转属性" width="420px" append-to-body>
      <div class="form-row">
        <label>流转名称</label>
        <el-input v-model="editingEdge.label" size="small" />
      </div>
      <div class="form-row">
        <label>流转类型</label>
        <el-radio-group v-model="editingEdge.skipType" size="small">
          <el-radio-button value="PASS">通过</el-radio-button>
          <el-radio-button value="REJECT">驳回</el-radio-button>
        </el-radio-group>
      </div>
      <template #footer>
        <el-button size="small" type="danger" text @click="deleteEditingEdge">删除连线</el-button>
        <el-button size="small" @click="edgeDialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="edgeDialogVisible = false">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { ElMessage, ElMessageBox } from 'element-plus'
import CustomNode from './CustomNode.vue'
import { listDefs, queryDesign, saveDef, publishDef, deleteDef } from '@/api/workflow'

// Vue Flow 必需样式，缺失会导致节点无法定位/拖动
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

const { screenToFlowPosition } = useVueFlow()

const nodePalette = [
  { type: 'start', label: '开始节点', icon: '▶', nodeType: 0, permission: '', ratio: '0.000' },
  { type: 'middle', label: '审批节点', icon: '✎', nodeType: 1, permission: 'role:APPROVER', ratio: '0.000' },
  { type: 'end', label: '结束节点', icon: '■', nodeType: 2, permission: '', ratio: '0.000' }
]

const rolePalette = [
  { code: 'OPERATOR', name: 'CA操作员' },
  { code: 'APPROVER', name: '审核员' },
  { code: 'RA_ADMIN', name: 'RA管理员' },
  { code: 'CERT_OFFICER', name: '证书签发操作员' },
  { code: 'COMPANY_ADMIN', name: '公司管理员' },
  { code: 'COMPANY_APPROVER', name: '公司审批员' },
  { code: 'KEY_ADMIN', name: '密钥管理员' },
  { code: 'SECURITY_ADMIN', name: '安全管理员' }
]

const defs = ref([])
const currentCode = ref('')
const def = ref({ flowCode: '', flowName: '', category: '', version: '1', id: null, isPublish: 0 })
const nodes = ref([])
const edges = ref([])
const selectedNode = ref(null)
const saving = ref(false)
const publishing = ref(false)

const edgeDialogVisible = ref(false)
const editingEdge = ref({})

let dragged = null

function onDragStart(e, item) {
  dragged = item
  e.dataTransfer.effectAllowed = 'move'
  // Firefox 要求必须写入数据才允许拖拽
  e.dataTransfer.setData('text/plain', item.type)
}

function onDrop(e) {
  if (!dragged) return
  const position = screenToFlowPosition({ x: e.clientX, y: e.clientY })
  const existing = new Set(nodes.value.map(n => n.id))
  let code = String(Date.now() % 100000)
  while (existing.has(code)) code = String(Number(code) + 1)

  nodes.value.push({
    id: code,
    type: dragged.type,
    position,
    data: {
      label: dragged.nodeType === 0 ? '开始' : dragged.nodeType === 2 ? '结束' : '审批节点',
      nodeType: dragged.nodeType,
      permission: dragged.permission,
      ratio: dragged.ratio
    }
  })
  dragged = null
}

function onNodeClick({ node }) {
  selectedNode.value = node
}

function onPaneClick() {
  selectedNode.value = null
}

function onConnect(params) {
  const { source, target } = params
  if (source === target) {
    ElMessage.warning('不能连到自身')
    return
  }
  const exists = edges.value.some(e => e.source === source && e.target === target)
  if (exists) {
    ElMessage.warning('该流转已存在')
    return
  }
  edges.value.push({
    id: `e-${source}-${target}-${Date.now()}`,
    source,
    target,
    label: '通过',
    skipType: 'PASS',
    data: { skipType: 'PASS' }
  })
}

function onEdgeClick({ edge }) {
  editingEdge.value = edge
  edgeDialogVisible.value = true
  nextTick(() => {
    // 同步下拉/输入框绑定到 edge 本身
    editingEdge.value = edge
  })
}

function deleteEditingEdge() {
  edges.value = edges.value.filter(e => e.id !== editingEdge.value.id)
  edgeDialogVisible.value = false
}

function deleteSelectedNode() {
  if (!selectedNode.value) return
  const id = selectedNode.value.id
  nodes.value = nodes.value.filter(n => n.id !== id)
  edges.value = edges.value.filter(e => e.source !== id && e.target !== id)
  selectedNode.value = null
}

// ===== 载入 / 新建 =====
async function refreshDefs() {
  const res = await listDefs()
  defs.value = Array.isArray(res?.data) ? res.data : []
}

async function loadDef(flowCode) {
  if (!flowCode) return
  const found = defs.value.find(d => d.flowCode === flowCode)
  if (!found) return
  const res = await queryDesign(found.id)
  const design = res?.data
  applyDesign(design, found)
  ElMessage.success(`已载入 ${found.flowName}`)
}

function applyDesign(design, meta) {
  def.value = {
    flowCode: design?.flowCode || meta.flowCode,
    flowName: design?.flowName || meta.flowName,
    category: design?.category || meta.category || '',
    version: design?.version || meta.version || '1',
    id: design?.id || meta.id,
    isPublish: meta.isPublish ?? design?.isPublish ?? 0
  }
  const nodeList = design?.nodeList || []
  const autoPos = buildAutoLayout(nodeList)
  nodes.value = nodeList.map(n => ({
    id: n.nodeCode,
    type: n.nodeType === 0 ? 'start' : n.nodeType === 2 ? 'end' : 'middle',
    position: parseCoordinate(n.coordinate) || autoPos[n.nodeCode],
    data: {
      label: n.nodeName,
      nodeType: n.nodeType,
      permission: n.permissionFlag || '',
      ratio: n.nodeRatio || '0.000'
    }
  }))
  edges.value = nodeList.flatMap(n =>
    (n.skipList || []).map(s => ({
      id: `e-${s.nowNodeCode}-${s.nextNodeCode}-${s.skipType || 'PASS'}`,
      source: s.nowNodeCode,
      target: s.nextNodeCode,
      label: s.skipName || (s.skipType === 'REJECT' ? '驳回' : '通过'),
      skipType: s.skipType || 'PASS',
      data: { skipType: s.skipType || 'PASS' }
    }))
  )
}

function buildAutoLayout(nodeList) {
  const map = {}
  nodeList.forEach((n, i) => {
    map[n.nodeCode] = { x: 80 + i * 200, y: 120 }
  })
  return map
}

function parseCoordinate(coord) {
  if (!coord) return null
  const first = coord.split('|')[0]
  const parts = first.split(',')
  const x = Number(parts[0])
  const y = Number(parts[1])
  if (Number.isNaN(x) || Number.isNaN(y)) return null
  return { x, y }
}

function newDef() {
  currentCode.value = ''
  def.value = { flowCode: '', flowName: '', category: '', version: '1', id: null, isPublish: 0 }
  nodes.value = []
  edges.value = []
  selectedNode.value = null
}

// ===== 保存 / 发布 / 删除 =====
function toDefJson() {
  const nodeList = nodes.value.map(n => ({
    nodeCode: n.id,
    nodeName: n.data.label,
    nodeType: n.data.nodeType,
    permissionFlag: n.data.permission || null,
    nodeRatio: n.data.ratio || '0.000',
    version: def.value.version,
    coordinate: `${Math.round(n.position.x)},${Math.round(n.position.y)}|${Math.round(n.position.x + 140)},${Math.round(n.position.y + 44)}`,
    skipList: edges.value
      .filter(e => e.source === n.id)
      .map(e => ({
        nowNodeCode: e.source,
        nextNodeCode: e.target,
        skipType: e.skipType || 'PASS',
        skipName: e.label || '',
        coordinate: ''
      }))
  }))
  return {
    id: def.value.id,
    flowCode: def.value.flowCode,
    flowName: def.value.flowName,
    category: def.value.category,
    version: def.value.version,
    formCustom: 'N',
    nodeList
  }
}

async function saveDraft() {
  if (!def.value.flowCode || !def.value.flowName) {
    ElMessage.warning('请填写流程编码和名称')
    return
  }
  if (!nodes.value.length) {
    ElMessage.warning('流程至少需要一个节点')
    return
  }
  saving.value = true
  try {
    const res = await saveDef(toDefJson())
    const id = res?.data
    if (id) def.value.id = id
    await refreshDefs()
    ElMessage.success('草稿已保存')
  } finally {
    saving.value = false
  }
}

async function publish() {
  if (!def.value.id) {
    ElMessage.warning('请先保存草稿')
    return
  }
  publishing.value = true
  try {
    await publishDef(def.value.id)
    def.value.isPublish = 1
    await refreshDefs()
    ElMessage.success('已发布，新提交的单据将使用此流程')
  } finally {
    publishing.value = false
  }
}

async function remove() {
  if (!def.value.id) return
  try {
    await ElMessageBox.confirm('确定删除该流程定义？将级联删除节点、流转、实例与历史记录', '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteDef(def.value.id)
  ElMessage.success('已删除')
  newDef()
  await refreshDefs()
}

refreshDefs()
</script>

<style scoped>
.wf-designer {
  height: calc(100vh - 80px);
  display: flex;
  flex-direction: column;
  background: #f8fafc;
}
.wf-toolbar {
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 4px;
}
.wf-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.wf-panel {
  width: 240px;
  background: #fff;
  border-right: 1px solid #e2e8f0;
  padding: 12px;
  overflow-y: auto;
  flex-shrink: 0;
}
.wf-panel-right {
  border-right: none;
  border-left: 1px solid #e2e8f0;
}
.panel-title {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 10px;
}
.palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px dashed #cbd5e1;
  border-radius: 6px;
  margin-bottom: 6px;
  cursor: grab;
  font-size: 13px;
  user-select: none;
  transition: all .15s;
}
.palette-item:hover {
  background: #f1f5f9;
  border-color: #3b82f6;
}
.palette-icon { width: 16px; text-align: center; color: #3b82f6 }
.role-chip {
  padding: 4px 8px;
  font-size: 12px;
  background: #f1f5f9;
  border-radius: 4px;
  margin-bottom: 4px;
}
.role-chip code { color: #0ea5e9; font-size: 11px }
.role-name { color: #94a3b8; margin-left: 4px }

.wf-canvas {
  flex: 1;
  position: relative;
  min-width: 0;
}
.canvas-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 14px;
  pointer-events: none;
}

.form-row { margin-bottom: 12px }
.form-row label {
  display: block;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 4px;
}
.form-hint { font-size: 11px; color: #94a3b8; margin-top: 2px }
.tips { font-size: 12px; color: #64748b; line-height: 1.9; padding-left: 18px }
.tips code { background: #f1f5f9; padding: 1px 4px; border-radius: 3px }
</style>
