import request from './request'

/**
 * 工作流定义与流程图（由后端 WorkflowRegistry 声明式驱动，前端按定义渲染流程图）。
 */

/** 全部工作流定义（用于总览页） */
export function listWorkflows() {
  return request.post('/admin/workflows/list', {})
}

/**
 * 按业务类型 + 当前状态推导流程图（含当前步高亮、下一步处理人提示）。
 * @param {string} bizType 业务类型（CERT_APPLICATION / CERT_OP / COMPANY_OP / SUB_CA_REBUILD）
 * @param {string} status 单据当前状态
 * @param {string} [assigneeRealName] 已指派的具体处理人（SINGLE_LEAST_BACKLOG 步骤用于展示具体人）
 */
export function getWorkflowDiagram(bizType, status, assigneeRealName) {
  return request.post('/admin/workflows/diagram', { bizType, status, assigneeRealName })
}

/**
 * 统一待办看板：四条审批流（RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建）里
 * 与我相关的单据，收敛成 {pending, initiated, done} 三组。
 */
export function getWorkflowTodoBoard() {
  return request.post('/admin/workflows/todo', {})
}

/**
 * 待办详情：一次返回待办行（含 actions）+ 完整业务行 + 审批轨迹 + 流程轨迹，
 * 供「我的待办」抽屉直接渲染，不再跳转到各业务台账页。
 * @param {string} bizType CERT_APPLICATION / CERT_OP / COMPANY_OP / SUB_CA_REBUILD
 * @param {string|number} bizId 单据 id
 */
export function getWorkflowTodoDetail(bizType, bizId) {
  return request.post('/admin/workflows/todo-detail', { bizType, bizId })
}

// ============ Warm-Flow 流程定义 CRUD（拖拽设计器用） ============

/** 列出所有流程定义 */
export function listDefs() {
  return request.post('/admin/workflow/defs/list', {})
}

/** 设计器选人下拉：全部启用用户 */
export function listAssignableUsers() {
  return request.post('/admin/workflow/defs/users', {})
}

/** 按 flowCode 查已发布的流程定义 */
export function getPublishedByCode(flowCode) {
  return request.post('/admin/workflow/defs/published', { flowCode })
}

/** 查询完整设计数据（定义+节点+跳转），供设计器回显 */
export function queryDesign(id) {
  return request.post('/admin/workflow/defs/design', { id })
}

/** 保存流程定义（新增或更新，草稿态），返回 id 字符串 */
export function saveDef(defJson) {
  return request.post('/admin/workflow/defs/save', defJson)
}

/** 保存并发布（设计器用：后端同一事务内先保存再发布），返回 id 字符串 */
export function saveAndPublishDef(defJson) {
  return request.post('/admin/workflow/defs/save-publish', defJson)
}

/** 发布流程定义（仅切换状态，列表页用） */
export function publishDef(id) {
  return request.post('/admin/workflow/defs/publish', { id })
}

/** 取消发布 */
export function unpublishDef(id) {
  return request.post('/admin/workflow/defs/unpublish', { id })
}

/** 删除流程定义 */
export function deleteDef(id) {
  return request.post('/admin/workflow/defs/delete', { id })
}

/** 导出 JSON 字符串 */
export function exportDef(id) {
  return request.post('/admin/workflow/defs/export', { id })
}
