<template>
  <div class="approval-flow">
    <!-- 说明卡 -->
    <div class="page-card intro-card">
      <div class="section-title">
        <el-icon>
          <Aim/>
        </el-icon>
        <span>审批流说明</span>
      </div>
      <el-alert
          :closable="false"
          description="系统里任一持有对应权限的角色成员均可审核，因此「下一个审核人」只能确定到角色（如 APPROVER），无法精确到具体人。提交后若长时间未审，请按下方图中的角色去找对应人员催办。"
          show-icon
          title="审批按「角色」而非「指派到人」"
          type="info"
      />
      <div class="quick-row">
        <span class="quick-label">快速入口：</span>
        <el-button size="small" @click="$router.push('/applications')">证书申请 / 审核</el-button>
        <el-button size="small" @click="$router.push('/company-ops')">公司变更审批</el-button>
        <el-button size="small" @click="$router.push('/certs')">证书操作审批</el-button>
      </div>
    </div>

    <!-- 流程图卡 -->
    <div class="page-card">
      <div class="section-title">全系统审批流总览</div>
      <p class="hint">紫色节点 =
        审核/审批角色，即「下一个审核人」。左侧为需双人复核的三类审批流，右侧为直接执行、无审批的特权操作。</p>
      <div class="flow-svg-wrap">
        <svg role="img" viewBox="0 0 680 825" width="100%">
          <title>全系统审批流总览</title>
          <desc>三类双人复核审批流（RA申请、证书操作单、公司变更单）与四类直接执行无审批的特权操作，标注每一步所需角色。
          </desc>
          <defs>
            <marker id="arrow" markerHeight="6" markerWidth="6" orient="auto-start-reverse" refX="8" refY="5"
                    viewBox="0 0 10 10">
              <path d="M2 1L8 5L2 9" fill="none" stroke="context-stroke" stroke-linecap="round" stroke-linejoin="round"
                    stroke-width="1.5"/>
            </marker>
          </defs>

          <text fill="#1f2937" font-family="sans-serif" font-size="15" font-weight="500" x="40" y="28">
            全系统审批流总览
          </text>
          <text fill="#444441" font-family="sans-serif" font-size="12" x="40" y="50">
            审批按角色而非指派到人；界面未点名角色，故不知道下一个审核人是谁
          </text>
          <rect fill="#EEEDFE" height="12" rx="2" stroke="#534AB7" stroke-width="0.5" width="12" x="40" y="64"/>
          <text fill="#3C3489" font-family="sans-serif" font-size="11" x="58" y="74">紫色 =
            审核/审批角色，即「下一个审核人」
          </text>

          <text fill="#2C2C2A" font-family="sans-serif" font-size="13" font-weight="500" text-anchor="middle" x="130"
                y="100">① RA 证书申请
          </text>
          <text fill="#2C2C2A" font-family="sans-serif" font-size="13" font-weight="500" text-anchor="middle" x="340"
                y="100">② 证书操作单
          </text>
          <text fill="#2C2C2A" font-family="sans-serif" font-size="13" font-weight="500" text-anchor="middle" x="550"
                y="100">③ 公司变更单
          </text>

          <!-- Lane 1 -->
          <g>
            <rect fill="#E6F1FB" height="40" rx="8" stroke="#185FA5" stroke-width="0.5" width="170" x="45" y="120"/>
            <text dominant-baseline="central" fill="#0C447C" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="130" y="140">OPERATOR 提交
            </text>
          </g>
          <path d="M130,160 L130,180" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#FAEEDA" height="40" rx="8" stroke="#BA7517" stroke-width="0.5" width="170" x="45" y="180"/>
            <text dominant-baseline="central" fill="#633806" font-family="sans-serif" font-size="13" text-anchor="middle"
                  x="130" y="200">待审核 PENDING
            </text>
          </g>
          <path d="M130,220 L130,240" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#EEEDFE" height="46" rx="8" stroke="#534AB7" stroke-width="1" width="170" x="45" y="240"/>
            <text dominant-baseline="central" fill="#3C3489" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="130" y="255">APPROVER 审核
            </text>
            <text dominant-baseline="central" fill="#534AB7" font-family="sans-serif" font-size="12" text-anchor="middle"
                  x="130" y="272">或 RA_ADMIN
            </text>
          </g>
          <path d="M130,286 L130,306" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#E6F1FB" height="40" rx="8" stroke="#185FA5" stroke-width="0.5" width="170" x="45" y="306"/>
            <text dominant-baseline="central" fill="#0C447C" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="130" y="326">OPERATOR 签发
            </text>
          </g>
          <path d="M130,346 L130,366" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#EAF3DE" height="40" rx="8" stroke="#3B6D11" stroke-width="0.5" width="170" x="45" y="366"/>
            <text dominant-baseline="central" fill="#27500A" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="130" y="386">已签发 ISSUED
            </text>
          </g>

          <!-- Lane 2 -->
          <g>
            <rect fill="#E6F1FB" height="46" rx="8" stroke="#185FA5" stroke-width="0.5" width="170" x="255" y="120"/>
            <text dominant-baseline="central" fill="#0C447C" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="340" y="135">OPERATOR 发起
            </text>
            <text dominant-baseline="central" fill="#185FA5" font-family="sans-serif" font-size="12" text-anchor="middle"
                  x="340" y="152">或 CERT_OFFICER
            </text>
          </g>
          <path d="M340,166 L340,186" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#FAEEDA" height="40" rx="8" stroke="#BA7517" stroke-width="0.5" width="170" x="255" y="186"/>
            <text dominant-baseline="central" fill="#633806" font-family="sans-serif" font-size="13" text-anchor="middle"
                  x="340" y="206">待审批 PENDING
            </text>
          </g>
          <path d="M340,226 L340,246" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#EEEDFE" height="46" rx="8" stroke="#534AB7" stroke-width="1" width="170" x="255" y="246"/>
            <text dominant-baseline="central" fill="#3C3489" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="340" y="261">APPROVER 审批
            </text>
            <text dominant-baseline="central" fill="#534AB7" font-family="sans-serif" font-size="12" text-anchor="middle"
                  x="340" y="278">即执行·唯一角色
            </text>
          </g>
          <path d="M340,292 L340,312" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#EAF3DE" height="40" rx="8" stroke="#3B6D11" stroke-width="0.5" width="170" x="255" y="312"/>
            <text dominant-baseline="central" fill="#27500A" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="340" y="332">已执行 EXECUTED
            </text>
          </g>

          <!-- Lane 3 -->
          <g>
            <rect fill="#E6F1FB" height="40" rx="8" stroke="#185FA5" stroke-width="0.5" width="170" x="465" y="120"/>
            <text dominant-baseline="central" fill="#0C447C" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="550" y="140">COMPANY_ADMIN 发起
            </text>
          </g>
          <path d="M550,160 L550,180" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#FAEEDA" height="40" rx="8" stroke="#BA7517" stroke-width="0.5" width="170" x="465" y="180"/>
            <text dominant-baseline="central" fill="#633806" font-family="sans-serif" font-size="13" text-anchor="middle"
                  x="550" y="200">待审批 PENDING
            </text>
          </g>
          <path d="M550,220 L550,240" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#EEEDFE" height="46" rx="8" stroke="#534AB7" stroke-width="1" width="170" x="465" y="240"/>
            <text dominant-baseline="central" fill="#3C3489" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="550" y="255">COMPANY_APPROVER
            </text>
            <text dominant-baseline="central" fill="#534AB7" font-family="sans-serif" font-size="12" text-anchor="middle"
                  x="550" y="272">审批即执行·唯一
            </text>
          </g>
          <path d="M550,286 L550,306" fill="none" marker-end="url(#arrow)" stroke="#5F5E5A" stroke-width="1.5"/>
          <g>
            <rect fill="#EAF3DE" height="40" rx="8" stroke="#3B6D11" stroke-width="0.5" width="170" x="465" y="306"/>
            <text dominant-baseline="central" fill="#27500A" font-family="sans-serif" font-size="13" font-weight="500"
                  text-anchor="middle" x="550" y="326">已执行 EXECUTED
            </text>
          </g>

          <!-- Bottom panels -->
          <g>
            <rect fill="#F1EFE8" height="350" rx="10" stroke="#5F5E5A" stroke-width="0.5" width="300" x="40" y="440"/>
            <text fill="#2C2C2A" font-family="sans-serif" font-size="13" font-weight="500" x="56" y="468">直接执行 ·
              无审批
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="56" y="498">新建公司 → COMPANY_ADMIN</text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="56" y="524">根CA操作(初始化/封存/解封/分持)
              → KEY_ADMIN
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="56" y="554">证书模板增删改 →
              SECURITY_ADMIN
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="56" y="580">用户/角色管理 → USER_ADMIN
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="56" y="606">CRL下载/OCSP查询/TSA盖章/API登记
              → 无审批
            </text>
          </g>
          <g>
            <rect fill="#F1EFE8" height="350" rx="10" stroke="#5F5E5A" stroke-width="0.5" width="280" x="360" y="440"/>
            <text fill="#2C2C2A" font-family="sans-serif" font-size="13" font-weight="500" x="376" y="468">
              根因：为何看不到下一个审核人
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="376" y="500">① 审批按「角色」，不指派到人
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="376" y="530">② hasPerm 隐藏按钮，提交人
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="392" y="550">侧根本看不到审批入口</text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="376" y="582">③ 无「待我审批」聚合页，审批人
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="392" y="602">也要手动翻对应页签</text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="376" y="640">④ 证书操作单连角色名都没点
            </text>
            <text fill="#444441" font-family="sans-serif" font-size="12" x="392" y="660">（只写"他人/持审批权限者"）
            </text>
          </g>

          <text fill="#6B7280" font-family="sans-serif" font-size="11" x="40" y="812">
            注：紫色角色可有多人；系统按角色而非指派到人，故只能确定「角色」不能确定「具体人」。
          </text>
        </svg>
      </div>
    </div>

    <!-- 审批流明细表 -->
    <div class="page-card">
      <div class="section-title">各业务审批角色对照</div>
      <el-table :data="flows" border stripe style="width: 100%">
        <el-table-column label="业务" min-width="220" prop="name"/>
        <el-table-column label="提交人（发起方）" min-width="160" prop="submitter"/>
        <el-table-column label="下一个审核人（角色）" min-width="180">
          <template #default="{ row }">
            <el-tag effect="light" type="warning">{{ row.approver }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行人" min-width="150" prop="executor"/>
        <el-table-column label="说明 / 制衡规则" min-width="260" prop="note"/>
      </el-table>
    </div>

    <!-- 角色职责卡 -->
    <div class="page-card">
      <div class="section-title">角色职责一览</div>
      <div class="role-grid">
        <div v-for="r in roles" :key="r.code" class="role-card">
          <div class="role-head">
            <el-tag :type="isApprover(r.code) ? 'warning' : 'info'" effect="light" size="small">{{ r.code }}</el-tag>
            <span class="role-name">{{ r.name }}</span>
          </div>
          <div class="role-desc">{{ r.desc }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import {Aim} from '@element-plus/icons-vue'

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
    name: '公司变更单（暂停/恢复/关闭/重置密钥）',
    submitter: 'COMPANY_ADMIN',
    approver: 'COMPANY_APPROVER（唯一）',
    executor: '—（审批即执行）',
    note: '发起人不得自批'
  }
]

const roles = [
  {code: 'OPERATOR', name: 'CA操作员/录入员', desc: '提交证书申请、按申请签发证书、发起证书操作单。无审批权。'},
  {code: 'APPROVER', name: '审核员', desc: '审核 RA 申请（批准/驳回）、审批证书操作单（批准即执行）。不可与提交人同一人。'},
  {code: 'RA_ADMIN', name: 'RA业务管理员', desc: '审核 RA 申请、撤销申请。'},
  {code: 'CERT_OFFICER', name: '证书专员', desc: '发起证书操作单（吊销/挂起/更新/重签）。'},
  {code: 'COMPANY_ADMIN', name: '公司管理员', desc: '发起公司变更单、新建公司、登记 API 客户端。'},
  {code: 'COMPANY_APPROVER', name: '公司审批员', desc: '审批公司变更单（批准即执行）。'},
  {code: 'KEY_ADMIN', name: '密钥管理员', desc: '根CA初始化/封存/解封/门限分持，直接执行。'},
  {code: 'SECURITY_ADMIN', name: '安全策略管理员', desc: '证书模板增删改，直接执行。'},
  {code: 'USER_ADMIN', name: '用户与角色管理员', desc: '账号与角色授权配置。'},
  {code: 'ADMIN', name: '系统管理员', desc: '只读巡检（概览/审计/用户查看），无审批权。'}
]

function isApprover(code) {
  return ['APPROVER', 'RA_ADMIN', 'COMPANY_APPROVER'].includes(code)
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
