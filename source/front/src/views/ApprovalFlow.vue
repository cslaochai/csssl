<template>
  <div class="approval-flow">
    <!-- 说明卡 -->
    <div class="page-card intro-card">
      <div class="section-title">
        <el-icon><Aim /></el-icon>
        <span>审批流说明</span>
      </div>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="审批按「角色」而非「指派到人」"
        description="系统里任一持有对应权限的角色成员均可审核，因此「下一个审核人」只能确定到角色（如 APPROVER），无法精确到具体人。提交后若长时间未审，请按下方图中的角色去找对应人员催办。"
      />
      <div class="quick-row">
        <span class="quick-label">快速入口：</span>
        <el-button size="small" @click="$router.push('/applications')">证书申请 / 审核</el-button>
        <el-button size="small" @click="$router.push('/tenant-ops')">租户变更审批</el-button>
        <el-button size="small" @click="$router.push('/certs')">证书操作审批</el-button>
      </div>
    </div>

    <!-- 流程图卡 -->
    <div class="page-card">
      <div class="section-title">全系统审批流总览</div>
      <p class="hint">紫色节点 = 审核/审批角色，即「下一个审核人」。左侧为需双人复核的三类审批流，右侧为直接执行、无审批的特权操作。</p>
      <div class="flow-svg-wrap">
        <svg viewBox="0 0 680 825" width="100%" role="img">
          <title>全系统审批流总览</title>
          <desc>三类双人复核审批流（RA申请、证书操作单、租户变更单）与四类直接执行无审批的特权操作，标注每一步所需角色。</desc>
          <defs>
            <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
              <path d="M2 1L8 5L2 9" fill="none" stroke="context-stroke" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </marker>
          </defs>

          <text x="40" y="28" font-family="sans-serif" font-size="15" font-weight="500" fill="#1f2937">全系统审批流总览</text>
          <text x="40" y="50" font-family="sans-serif" font-size="12" fill="#444441">审批按角色而非指派到人；界面未点名角色，故不知道下一个审核人是谁</text>
          <rect x="40" y="64" width="12" height="12" rx="2" fill="#EEEDFE" stroke="#534AB7" stroke-width="0.5"/>
          <text x="58" y="74" font-family="sans-serif" font-size="11" fill="#3C3489">紫色 = 审核/审批角色，即「下一个审核人」</text>

          <text x="130" y="100" text-anchor="middle" font-family="sans-serif" font-size="13" font-weight="500" fill="#2C2C2A">① RA 证书申请</text>
          <text x="340" y="100" text-anchor="middle" font-family="sans-serif" font-size="13" font-weight="500" fill="#2C2C2A">② 证书操作单</text>
          <text x="550" y="100" text-anchor="middle" font-family="sans-serif" font-size="13" font-weight="500" fill="#2C2C2A">③ 租户变更单</text>

          <!-- Lane 1 -->
          <g>
            <rect x="45" y="120" width="170" height="40" rx="8" fill="#E6F1FB" stroke="#185FA5" stroke-width="0.5"/>
            <text x="130" y="140" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#0C447C">OPERATOR 提交</text>
          </g>
          <path d="M130,160 L130,180" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="45" y="180" width="170" height="40" rx="8" fill="#FAEEDA" stroke="#BA7517" stroke-width="0.5"/>
            <text x="130" y="200" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" fill="#633806">待审核 PENDING</text>
          </g>
          <path d="M130,220 L130,240" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="45" y="240" width="170" height="46" rx="8" fill="#EEEDFE" stroke="#534AB7" stroke-width="1"/>
            <text x="130" y="255" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#3C3489">APPROVER 审核</text>
            <text x="130" y="272" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="12" fill="#534AB7">或 RA_ADMIN</text>
          </g>
          <path d="M130,286 L130,306" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="45" y="306" width="170" height="40" rx="8" fill="#E6F1FB" stroke="#185FA5" stroke-width="0.5"/>
            <text x="130" y="326" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#0C447C">OPERATOR 签发</text>
          </g>
          <path d="M130,346 L130,366" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="45" y="366" width="170" height="40" rx="8" fill="#EAF3DE" stroke="#3B6D11" stroke-width="0.5"/>
            <text x="130" y="386" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#27500A">已签发 ISSUED</text>
          </g>

          <!-- Lane 2 -->
          <g>
            <rect x="255" y="120" width="170" height="46" rx="8" fill="#E6F1FB" stroke="#185FA5" stroke-width="0.5"/>
            <text x="340" y="135" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#0C447C">OPERATOR 发起</text>
            <text x="340" y="152" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="12" fill="#185FA5">或 CERT_OFFICER</text>
          </g>
          <path d="M340,166 L340,186" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="255" y="186" width="170" height="40" rx="8" fill="#FAEEDA" stroke="#BA7517" stroke-width="0.5"/>
            <text x="340" y="206" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" fill="#633806">待审批 PENDING</text>
          </g>
          <path d="M340,226 L340,246" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="255" y="246" width="170" height="46" rx="8" fill="#EEEDFE" stroke="#534AB7" stroke-width="1"/>
            <text x="340" y="261" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#3C3489">APPROVER 审批</text>
            <text x="340" y="278" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="12" fill="#534AB7">即执行·唯一角色</text>
          </g>
          <path d="M340,292 L340,312" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="255" y="312" width="170" height="40" rx="8" fill="#EAF3DE" stroke="#3B6D11" stroke-width="0.5"/>
            <text x="340" y="332" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#27500A">已执行 EXECUTED</text>
          </g>

          <!-- Lane 3 -->
          <g>
            <rect x="465" y="120" width="170" height="40" rx="8" fill="#E6F1FB" stroke="#185FA5" stroke-width="0.5"/>
            <text x="550" y="140" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#0C447C">TENANT_ADMIN 发起</text>
          </g>
          <path d="M550,160 L550,180" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="465" y="180" width="170" height="40" rx="8" fill="#FAEEDA" stroke="#BA7517" stroke-width="0.5"/>
            <text x="550" y="200" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" fill="#633806">待审批 PENDING</text>
          </g>
          <path d="M550,220 L550,240" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="465" y="240" width="170" height="46" rx="8" fill="#EEEDFE" stroke="#534AB7" stroke-width="1"/>
            <text x="550" y="255" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#3C3489">TENANT_APPROVER</text>
            <text x="550" y="272" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="12" fill="#534AB7">审批即执行·唯一</text>
          </g>
          <path d="M550,286 L550,306" fill="none" stroke="#5F5E5A" stroke-width="1.5" marker-end="url(#arrow)"/>
          <g>
            <rect x="465" y="306" width="170" height="40" rx="8" fill="#EAF3DE" stroke="#3B6D11" stroke-width="0.5"/>
            <text x="550" y="326" text-anchor="middle" dominant-baseline="central" font-family="sans-serif" font-size="13" font-weight="500" fill="#27500A">已执行 EXECUTED</text>
          </g>

          <!-- Bottom panels -->
          <g>
            <rect x="40" y="440" width="300" height="350" rx="10" fill="#F1EFE8" stroke="#5F5E5A" stroke-width="0.5"/>
            <text x="56" y="468" font-family="sans-serif" font-size="13" font-weight="500" fill="#2C2C2A">直接执行 · 无审批</text>
            <text x="56" y="498" font-family="sans-serif" font-size="12" fill="#444441">新建租户 → TENANT_ADMIN</text>
            <text x="56" y="524" font-family="sans-serif" font-size="12" fill="#444441">根CA操作(初始化/封存/解封/分持) → KEY_ADMIN</text>
            <text x="56" y="554" font-family="sans-serif" font-size="12" fill="#444441">证书模板增删改 → SECURITY_ADMIN</text>
            <text x="56" y="580" font-family="sans-serif" font-size="12" fill="#444441">用户/角色管理 → USER_ADMIN</text>
            <text x="56" y="606" font-family="sans-serif" font-size="12" fill="#444441">CRL下载/OCSP查询/TSA盖章/API登记 → 无审批</text>
          </g>
          <g>
            <rect x="360" y="440" width="280" height="350" rx="10" fill="#F1EFE8" stroke="#5F5E5A" stroke-width="0.5"/>
            <text x="376" y="468" font-family="sans-serif" font-size="13" font-weight="500" fill="#2C2C2A">根因：为何看不到下一个审核人</text>
            <text x="376" y="500" font-family="sans-serif" font-size="12" fill="#444441">① 审批按「角色」，不指派到人</text>
            <text x="376" y="530" font-family="sans-serif" font-size="12" fill="#444441">② hasPerm 隐藏按钮，提交人</text>
            <text x="392" y="550" font-family="sans-serif" font-size="12" fill="#444441">侧根本看不到审批入口</text>
            <text x="376" y="582" font-family="sans-serif" font-size="12" fill="#444441">③ 无「待我审批」聚合页，审批人</text>
            <text x="392" y="602" font-family="sans-serif" font-size="12" fill="#444441">也要手动翻对应页签</text>
            <text x="376" y="640" font-family="sans-serif" font-size="12" fill="#444441">④ 证书操作单连角色名都没点</text>
            <text x="392" y="660" font-family="sans-serif" font-size="12" fill="#444441">（只写"他人/持审批权限者"）</text>
          </g>

          <text x="40" y="812" font-family="sans-serif" font-size="11" fill="#6B7280">注：紫色角色可有多人；系统按角色而非指派到人，故只能确定「角色」不能确定「具体人」。</text>
        </svg>
      </div>
    </div>

    <!-- 审批流明细表 -->
    <div class="page-card">
      <div class="section-title">各业务审批角色对照</div>
      <el-table :data="flows" border stripe style="width: 100%">
        <el-table-column prop="name" label="业务" min-width="220" />
        <el-table-column prop="submitter" label="提交人（发起方）" min-width="160" />
        <el-table-column label="下一个审核人（角色）" min-width="180">
          <template #default="{ row }">
            <el-tag type="warning" effect="light">{{ row.approver }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="executor" label="执行人" min-width="150" />
        <el-table-column prop="note" label="说明 / 制衡规则" min-width="260" />
      </el-table>
    </div>

    <!-- 角色职责卡 -->
    <div class="page-card">
      <div class="section-title">角色职责一览</div>
      <div class="role-grid">
        <div v-for="r in roles" :key="r.code" class="role-card">
          <div class="role-head">
            <el-tag size="small" :type="isApprover(r.code) ? 'warning' : 'info'" effect="light">{{ r.code }}</el-tag>
            <span class="role-name">{{ r.name }}</span>
          </div>
          <div class="role-desc">{{ r.desc }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { Aim } from '@element-plus/icons-vue'

const flows = [
  {
    name: 'RA 证书申请',
    submitter: 'OPERATOR（录入员）',
    approver: 'APPROVER / RA_ADMIN',
    executor: 'OPERATOR（签发）',
    note: '审核人≠提交人；审核后详情显示审核人/时间/意见'
  },
  {
    name: '证书操作单（吊销/挂起/更新/重签）',
    submitter: 'OPERATOR / CERT_OFFICER',
    approver: 'APPROVER（唯一）',
    executor: '—（审批即执行，写入 CRL/OCSP）',
    note: '审批即执行；建议证书详情补全审批人'
  },
  {
    name: '租户变更单（暂停/恢复/关闭/重置密钥）',
    submitter: 'TENANT_ADMIN',
    approver: 'TENANT_APPROVER（唯一）',
    executor: '—（审批即执行）',
    note: '发起人不得自批'
  }
]

const roles = [
  { code: 'OPERATOR', name: 'CA操作员/录入员', desc: '提交证书申请、按申请签发证书、发起证书操作单。无审批权。' },
  { code: 'APPROVER', name: '审核员', desc: '审核 RA 申请（批准/驳回）、审批证书操作单（批准即执行）。不可与提交人同一人。' },
  { code: 'RA_ADMIN', name: 'RA业务管理员', desc: '审核 RA 申请、撤销申请。' },
  { code: 'CERT_OFFICER', name: '证书专员', desc: '发起证书操作单（吊销/挂起/更新/重签）。' },
  { code: 'TENANT_ADMIN', name: '租户管理员', desc: '发起租户变更单、新建租户、登记 API 客户端。' },
  { code: 'TENANT_APPROVER', name: '租户审批员', desc: '审批租户变更单（批准即执行）。' },
  { code: 'KEY_ADMIN', name: '密钥管理员', desc: '根CA初始化/封存/解封/门限分持，直接执行。' },
  { code: 'SECURITY_ADMIN', name: '安全策略管理员', desc: '证书模板增删改，直接执行。' },
  { code: 'USER_ADMIN', name: '用户与角色管理员', desc: '账号与角色授权配置。' },
  { code: 'ADMIN', name: '系统管理员', desc: '只读巡检（概览/审计/用户查看），无审批权。' }
]

function isApprover(code) {
  return ['APPROVER', 'RA_ADMIN', 'TENANT_APPROVER'].includes(code)
}
</script>

<style scoped>
.approval-flow {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title, #1f2937);
  margin-bottom: 14px;
}

.hint {
  margin: -6px 0 14px;
  font-size: 12px;
  color: #8496a8;
}

.quick-row {
  margin-top: 14px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.quick-label {
  font-size: 13px;
  color: #5b7893;
}

.flow-svg-wrap {
  width: 100%;
  overflow-x: auto;
}

.flow-svg-wrap svg {
  display: block;
  width: 100%;
  max-width: 720px;
  height: auto;
  margin: 0 auto;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}

.role-card {
  border: 1px solid var(--his-card-border, #e3e9f2);
  border-radius: var(--his-radius, 8px);
  padding: 12px 14px;
  background: #f8fbfe;
}

.role-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.role-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--his-title, #1f2937);
}

.role-desc {
  font-size: 12px;
  line-height: 1.6;
  color: #5b7893;
}
</style>
