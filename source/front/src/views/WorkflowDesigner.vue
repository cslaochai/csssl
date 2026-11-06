<template>
  <div class="wf-designer">
    <!-- 顶部工具栏 -->
    <div class="wf-toolbar">
      <el-button size="small" @click="backToList">
        <el-icon style="margin-right:4px"><ArrowLeft /></el-icon>返回流程列表
      </el-button>

      <el-divider direction="vertical" />

      <el-tag v-if="loadingDef">载入中…</el-tag>
      <el-input v-model="def.flowName" placeholder="流程名称" style="width:170px" size="small" :disabled="loadingDef" />
      <el-input
        v-model="def.flowCode"
        placeholder="流程编码(唯一)"
        style="width:170px;margin-left:8px"
        size="small"
        :disabled="!!def.id || loadingDef"
      />
      <el-input v-model="def.category" placeholder="分类" style="width:120px;margin-left:8px" size="small" :disabled="loadingDef" />

      <el-tag v-if="def.isPublish === 1" type="success" style="margin-left:16px">已发布</el-tag>
      <el-tag v-else-if="def.id" type="warning" style="margin-left:16px">草稿</el-tag>
      <el-tag v-else type="info" style="margin-left:16px">新建</el-tag>

      <div style="margin-left:auto;display:flex;gap:8px">
        <el-button v-if="nodes.length > 1" size="small" @click="autoLayout">
          <el-icon style="margin-right:4px"><MagicStick /></el-icon>一键美化
        </el-button>
        <el-button size="small" @click="saveDraft" :loading="saving">保存草稿</el-button>
        <el-button size="small" type="success" :loading="publishing" @click="publish">发布</el-button>
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
          <span class="palette-icon" :style="{ color: n.color }">{{ n.icon }}</span>
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
      <div class="wf-canvas">
        <div
          class="wf-drop-zone"
          @drop.stop.prevent="onDrop"
          @dragover.prevent
        >
          <VueFlow
            v-model:nodes="nodes"
            v-model:edges="edges"
            :node-types="nodeTypes"
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
            @error="onFlowError"
          >
            <Background :gap="16" pattern-color="#e2e8f0" />
            <Controls position="bottom-left" />
            <MiniMap position="bottom-right" />
          </VueFlow>
        </div>

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
          <div v-if="selectedNode.data.nodeType === 1" class="form-row">
            <label>办理人方式</label>
            <el-radio-group v-model="assigneeType" size="small" @change="onAssigneeTypeChange">
              <el-radio-button value="role">按角色</el-radio-button>
              <el-radio-button value="user">固定人员</el-radio-button>
            </el-radio-group>
          </div>
          <div v-if="selectedNode.data.nodeType === 1 && assigneeType === 'role'" class="form-row">
            <label>办理角色</label>
            <el-select v-model="assigneeValues" multiple size="small" style="width:100%" placeholder="选择角色">
              <el-option v-for="r in rolePalette" :key="r.code" :label="r.name" :value="r.code" />
            </el-select>
            <div class="form-hint">角色下所有启用用户都可办理，人员变动自动生效</div>
          </div>
          <div v-if="selectedNode.data.nodeType === 1 && assigneeType === 'user'" class="form-row">
            <label>固定人员</label>
            <el-select v-model="assigneeValues" multiple filterable size="small" style="width:100%" placeholder="搜索并选择具体人员">
              <el-option
                v-for="u in assignableUsers"
                :key="u.username"
                :label="u.realName + '（' + u.username + '）'"
                :value="u.username"
              />
            </el-select>
            <div class="form-hint">流程固定由所选人员办理，人员停用后发布会被拦截</div>
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
import { ref, markRaw, watch, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { ElMessage } from 'element-plus'
import { ArrowLeft, MagicStick } from '@element-plus/icons-vue'
import CustomNode from './CustomNode.vue'
import { queryDesign, saveDef, saveAndPublishDef, listAssignableUsers } from '@/api/workflow'
import { listRoles } from '@/api/system'

// Vue Flow 必需样式，缺失会导致节点无法定位/拖动
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

// 显式注册节点类型，避免 slot 解析在某些 Vue Flow 版本下失效；markRaw 防止 Vue 深层代理组件对象
const nodeTypes = {
  start: markRaw(CustomNode),
  middle: markRaw(CustomNode),
  end: markRaw(CustomNode)
}

// @vue-flow/core 1.x 暴露的是 screenToFlowCoordinate；screenToFlowPosition 是 React Flow 的叫法，此处取到 undefined
const { screenToFlowCoordinate, fitView } = useVueFlow()

// 必须 v-model 双向绑定：只传 :nodes 时拖动坐标仅落在 VueFlow 内部副本，父数组一变就回退原位
function onFlowError(err) {
  console.error('[VueFlow error]', err)
}

const nodePalette = [
  { type: 'start', label: '开始节点', icon: '●', color: '#10b981', nodeType: 0, permission: '', ratio: '0.000' },
  { type: 'middle', label: '审批节点', icon: '✎', color: '#3b82f6', nodeType: 1, permission: 'role:APPROVER', ratio: '0.000' },
  { type: 'end', label: '结束节点', icon: '○', color: '#6b7280', nodeType: 2, permission: '', ratio: '0.000' }
]

const rolePalette = ref([])

const route = useRoute()
const router = useRouter()

const def = ref({ flowCode: '', flowName: '', category: '', version: '1', id: null, isPublish: 0 })
const nodes = ref([])
const edges = ref([])
const selectedNode = ref(null)
const saving = ref(false)
const publishing = ref(false)
const loadingDef = ref(false)
// 办理人配置：role 按角色 / user 固定人员；两者最终都落到节点的 permission 字符串
const assigneeType = ref('role')
const assigneeValues = ref([])
const assignableUsers = ref([])
let syncingAssignee = false

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
  const flowPos = screenToFlowCoordinate({ x: e.clientX, y: e.clientY })
  const existing = new Set(nodes.value.map(n => n.id))
  let code = String(Date.now() % 100000)
  while (existing.has(code)) code = String(Number(code) + 1)

  const newNode = {
    id: code,
    type: dragged.type,
    position: flowPos,
    data: {
      label: dragged.nodeType === 0 ? '开始' : dragged.nodeType === 2 ? '结束' : '审批节点',
      nodeType: dragged.nodeType,
      permission: dragged.permission,
      ratio: dragged.ratio
    }
  }
  nodes.value = [...nodes.value, newNode]
  dragged = null
}

function onNodeClick({ node }) {
  selectedNode.value = node
  syncAssigneeFromNode(node)
}

/** permission 字符串 → 办理人方式与取值 */
function syncAssigneeFromNode(node) {
  syncingAssignee = true
  const flags = String(node?.data?.permission || '')
    .split('@@')
    .map(s => s.trim())
    .filter(Boolean)
  const isUser = flags.some(f => f.startsWith('user:'))
  const values = flags.map(f => {
    if (f.startsWith('role:')) return f.slice(5)
    if (f.startsWith('user:')) return f.slice(5)
    return f
  })
  assigneeType.value = isUser ? 'user' : 'role'
  assigneeValues.value = values
  nextTick(() => {
    syncingAssignee = false
  })
}

function onAssigneeTypeChange() {
  // 切换方式后旧取值不通用，清空重选
  assigneeValues.value = []
}

watch(
  [assigneeType, assigneeValues],
  () => {
    if (syncingAssignee || !selectedNode.value) return
    if (selectedNode.value.data.nodeType !== 1) return
    const prefix = assigneeType.value === 'role' ? 'role:' : 'user:'
    selectedNode.value.data.permission = assigneeValues.value.map(v => prefix + v).join('@@')
  },
  { deep: true, flush: 'sync' }
)

async function loadAssignableUsers() {
  try {
    const res = await listAssignableUsers()
    assignableUsers.value = res?.data || []
  } catch (e) {
    assignableUsers.value = []
  }
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

// ===== 载入 / 返回 =====
function backToList() {
  router.push({ name: 'workflow-management' })
}

function resetBlank() {
  def.value = { flowCode: '', flowName: '', category: '', version: '1', id: null, isPublish: 0 }
  nodes.value = []
  edges.value = []
  selectedNode.value = null
}

async function loadDefById(id) {
  loadingDef.value = true
  try {
    const res = await queryDesign(id)
    const design = res?.data
    if (!design) {
      ElMessage.error('流程定义不存在')
      backToList()
      return
    }
    def.value = {
      flowCode: design.flowCode || '',
      flowName: design.flowName || '',
      category: design.category || '',
      version: design.version || '1',
      id: design.id == null ? null : String(design.id),
      isPublish: design.isPublish ?? 0
    }
    const nodeList = design.nodeList || []
    const autoPos = buildAutoLayout(nodeList)
    nodes.value = nodeList.map(n => ({
      id: String(n.nodeCode),
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
  } catch (e) {
    ElMessage.error(e?.message || '载入失败')
    backToList()
  } finally {
    loadingDef.value = false
  }
}

function setupFromRoute() {
  const id = route.params.id
  if (id && id !== 'undefined') {
    loadDefById(id)
  } else {
    resetBlank()
  }
}

watch(() => route.params.id, (id, old) => {
  if (id === old) return
  if (id && id !== 'undefined') {
    loadDefById(id)
  } else {
    resetBlank()
  }
})

onMounted(() => {
  setupFromRoute()
  loadAssignableUsers()
  loadRolePalette()
})

async function loadRolePalette() {
  try {
    const res = await listRoles()
    rolePalette.value = (res.data || [])
      .filter(r => r.status === 1)
      .map(r => ({ code: r.roleCode, name: r.roleName }))
  } catch (e) {
    console.error('加载角色列表失败', e)
  }
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

// ===== 一键美化：按流转拓扑分层，从左到右逐列排开 =====
const LAYOUT_GAP_X = 72
const LAYOUT_GAP_Y = 48
const NODE_FALLBACK_W = 180
const NODE_FALLBACK_H = 90

function autoLayout() {
  if (nodes.value.length < 2) return
  const outgoing = new Map()
  edges.value.forEach(e => {
    if (!outgoing.has(e.source)) outgoing.set(e.source, [])
    outgoing.get(e.source).push(e.target)
  })
  const hasIncoming = new Set(edges.value.map(e => e.target))

  const depth = new Map()
  let frontier = nodes.value
    .filter(n => n.data.nodeType === 0 || !hasIncoming.has(n.id))
    .map(n => { depth.set(n.id, 0); return n.id })
  if (!frontier.length) frontier = [nodes.value[0].id]
  while (frontier.length) {
    const id = frontier.shift()
    const d = depth.get(id)
    ;(outgoing.get(id) || []).forEach(t => {
      if (depth.has(t)) return
      depth.set(t, d + 1)
      frontier.push(t)
    })
  }

  const columns = new Map()
  nodes.value.forEach(n => {
    if (!depth.has(n.id)) depth.set(n.id, 0)
    const d = depth.get(n.id)
    if (!columns.has(d)) columns.set(d, [])
    columns.get(d).push(n)
  })

  let x = 60
  ;[...columns.keys()].sort((a, b) => a - b).forEach(d => {
    const col = columns.get(d).sort((a, b) => a.position.y - b.position.y)
    let y = 60
    col.forEach(n => {
      n.position = { x, y }
      y += (n.dimensions?.height || NODE_FALLBACK_H) + LAYOUT_GAP_Y
    })
    x += Math.max(...col.map(n => n.dimensions?.width || NODE_FALLBACK_W)) + LAYOUT_GAP_X
  })
  nextTick(() => fitView({ padding: 0.2 }))
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

function validateForSave() {
  if (!def.value.flowCode || !def.value.flowName) {
    ElMessage.warning('请填写流程编码和名称')
    return false
  }
  if (!nodes.value.length) {
    ElMessage.warning('流程至少需要一个节点')
    return false
  }
  return true
}

/** 保存当前画布内容，返回流程 id；调用前需先通过 validateForSave */
async function persistDraft() {
  const wasNew = !def.value.id
  const res = await saveDef(toDefJson())
  const id = res?.data
  if (!id) return null
  // 雪花 ID 必须按字符串保存，否则超过 JS 安全整数范围会精度丢失
  def.value.id = String(id)
  // 新建流程保存后，更新 URL 带上 id，避免刷新丢失
  if (wasNew) {
    router.replace({ name: 'workflow-designer', params: { id: def.value.id } })
  }
  return def.value.id
}

async function saveDraft() {
  if (!validateForSave()) return
  saving.value = true
  try {
    if (await persistDraft()) ElMessage.success('草稿已保存')
  } catch (e) {
    // 接口层已弹出后端返回的原因，这里只吞掉 rejection
  } finally {
    saving.value = false
  }
}

async function publish() {
  if (!validateForSave()) return
  publishing.value = true
  try {
    // 后端在同一事务里先保存当前画布、再发布：保存失败则不发布，发布失败则保存回滚
    const res = await saveAndPublishDef(toDefJson())
    const id = res?.data
    if (!id) throw new Error('发布失败')
    const wasNew = !def.value.id
    def.value.id = String(id)
    if (wasNew) {
      router.replace({ name: 'workflow-designer', params: { id: def.value.id } })
    }
    def.value.isPublish = 1
    ElMessage.success('已保存并发布，新提交的单据将使用此流程')
  } catch (e) {
    // 接口层已弹出后端校验原因（开始/结束节点数量、审批节点缺通过流转线等）
  } finally {
    publishing.value = false
  }
}
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
.wf-drop-zone {
  position: absolute;
  inset: 0;
  overflow: hidden;
}
.wf-drop-zone :deep(.vue-flow) {
  width: 100%;
  height: 100%;
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
