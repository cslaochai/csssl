<template>
  <div>
    <!-- RA申请（申请人）：提交申请 + 我的申请单 -->
    <div v-if="!isReviewMode">
      <!-- 查询卡 -->
      <el-card ref="queryCardRef" class="query-card" shadow="never">
        <div class="query-wrap">
          <el-form :model="query" inline>
            <el-form-item label="公司">
              <el-select v-model="query.companyUscc" clearable placeholder="全部" style="width: 280px"
                         @change="handleSearch">
                <el-option
                    v-for="o in companyOptions"
                    :key="o.uscc"
                    :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                    :value="o.uscc"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="query.status" clearable placeholder="全部" style="width: 130px"
                         @change="handleSearch">
                <el-option label="待审核" value="PENDING"/>
                <el-option label="已批准" value="APPROVED"/>
                <el-option label="已驳回" value="REJECTED"/>
                <el-option label="已签发" value="ISSUED"/>
                <el-option label="已撤销" value="CANCELLED"/>
              </el-select>
            </el-form-item>
            <el-form-item label="关键字">
              <el-input
                  v-model="query.keyword"
                  clearable
                  placeholder="申请人/申请单号"
                  style="width: 220px"
                  @clear="handleSearch"
                  @keyup.enter="handleSearch"
              />
            </el-form-item>
            <el-form-item>
              <el-button :icon="Search" type="primary" @click="handleSearch">搜索</el-button>
              <el-button :icon="Refresh" @click="handleReset">重置</el-button>
            </el-form-item>
          </el-form>
          <div class="query-actions">
            <el-button v-perm="'app:submit'" type="primary" @click="openSubmit">提交申请</el-button>
          </div>
        </div>
      </el-card>

      <!-- 表格卡 -->
      <el-card class="table-card" shadow="never">
        <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
          <el-table-column class-name="cell-mono" label="申请单号" prop="applyNo" show-overflow-tooltip width="180"/>
          <el-table-column label="申请人类别" width="100">
            <template #default="{ row }">{{ row.applicantType === 'INSTITUTION' ? '单位' : '个人' }}</template>
          </el-table-column>
          <el-table-column label="申请人" min-width="140" prop="applicantName" show-overflow-tooltip/>
          <el-table-column label="所属公司" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ companyNames[row.companyUscc] || row.companyUscc || '-' }}</template>
          </el-table-column>
          <el-table-column label="模板" width="100">
            <template #default="{ row }">{{ templateName(row.templateId) }}</template>
          </el-table-column>
          <el-table-column label="主体DN" min-width="180" prop="subjectDn" show-overflow-tooltip/>
          <el-table-column label="提交人" width="120">
            <template #default="{ row }">{{ row.appliedByName || '无' }}</template>
          </el-table-column>
          <el-table-column label="指派审批人" show-overflow-tooltip width="120">
            <template #default="{ row }">{{ row.assigneeRealName || '无' }}</template>
          </el-table-column>
          <el-table-column label="审核人" width="100">
            <template #default="{ row }">{{ row.reviewedByName }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column align="right" fixed="right" label="操作" width="230">
            <template #default="{ row }">
              <el-button
                  v-for="a in visibleRowActions(row)"
                  :key="a.label"
                  :type="a.type || 'primary'"
                  link
                  size="small"
                  @click="a.run()"
              >{{ a.label }}
              </el-button>
              <el-dropdown v-if="moreRowActions(row).length" trigger="click" @command="cmd => cmd.run()">
                <el-button link size="small" type="primary">
                  更多
                  <el-icon class="el-icon--right">
                    <ArrowDown/>
                  </el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                        v-for="a in moreRowActions(row)"
                        :key="a.label"
                        :command="a"
                    >{{ a.label }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
        <div ref="footerRef" class="list-footer">
          <el-pagination
              v-model:current-page="query.pageNo"
              v-model:page-size="query.pageSize"
              :page-sizes="PAGE_SIZES"
              :total="total"
              layout="total, sizes, prev, pager, next, jumper"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
          />
        </div>
      </el-card>
    </div>

    <!-- RA申请审核：申请单 + 证书操作单合并列表，按单据类型筛选 -->
    <div v-else>
      <el-card ref="queryCardRef" class="query-card" shadow="never">
        <div class="query-wrap">
          <el-form :model="reviewQuery" inline>
            <el-form-item label="单据类型">
              <el-select v-model="reviewQuery.docType" clearable placeholder="全部" style="width: 130px"
                         @change="handleReviewSearch">
                <el-option label="证书申请单" value="APPLICATION"/>
                <el-option label="证书操作单" value="OP_TICKET"/>
              </el-select>
            </el-form-item>
            <el-form-item label="公司">
              <el-select v-model="reviewQuery.companyUscc" clearable placeholder="全部" style="width: 280px"
                         @change="handleReviewSearch">
                <el-option v-for="o in companyOptions" :key="o.uscc"
                           :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                           :value="o.uscc"/>
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="reviewQuery.status" clearable placeholder="全部" style="width: 130px"
                         @change="handleReviewSearch">
                <el-option label="待处理" value="PENDING"/>
                <el-option label="已批准" value="APPROVED"/>
                <el-option label="已执行" value="EXECUTED"/>
                <el-option label="已签发" value="ISSUED"/>
                <el-option label="已驳回" value="REJECTED"/>
                <el-option label="已撤销" value="CANCELLED"/>
              </el-select>
            </el-form-item>
            <el-form-item label="关键字">
              <el-input v-model="reviewQuery.keyword" clearable placeholder="单号 / 申请人 / 证书主体"
                        style="width: 220px" @clear="handleReviewSearch" @keyup.enter="handleReviewSearch"/>
            </el-form-item>
            <el-form-item>
              <el-button :icon="Search" type="primary" @click="handleReviewSearch">搜索</el-button>
              <el-button :icon="Refresh" @click="handleReviewReset">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-card>

      <el-card class="table-card" shadow="never">
        <el-table v-loading="reviewLoading" :data="reviewPageRows" :max-height="tableMaxHeight" stripe
                  style="width: 100%">
          <el-table-column label="单据类型" width="110">
            <template #default="{ row }">
              <el-tag :type="row.docType === 'APPLICATION' ? 'primary' : 'warning'" size="small">
                {{ row.docType === 'APPLICATION' ? '证书申请单' : '证书操作单' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column class-name="cell-mono" label="单号" prop="docNo" show-overflow-tooltip width="180"/>
          <el-table-column label="摘要" min-width="230" prop="summary" show-overflow-tooltip/>
          <el-table-column label="所属公司" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ companyNames[row.companyUscc] || row.companyUscc || '-' }}</template>
          </el-table-column>
          <el-table-column label="提交人" prop="requestedByName" width="120"/>
          <el-table-column label="指派审批人" width="120">
            <template #default="{ row }">{{ row.assigneeRealName || '未指派' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.statusTag" size="small">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" prop="submittedAt" width="170"/>
          <el-table-column align="right" fixed="right" label="操作" width="260">
            <template #default="{ row }">
              <el-button
                  v-for="a in reviewRowActions(row)"
                  :key="a.label"
                  :type="a.type || 'primary'"
                  link
                  size="small"
                  @click="a.run()"
              >{{ a.label }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="list-footer">
          <el-pagination
              v-model:current-page="reviewQuery.pageNo"
              v-model:page-size="reviewQuery.pageSize"
              :page-sizes="PAGE_SIZES"
              :total="reviewFiltered.length"
              layout="total, sizes, prev, pager, next, jumper"
          />
        </div>
      </el-card>
    </div>

    <!-- 提交申请 -->
    <el-dialog v-model="submitVisible" :close-on-click-modal="false" title="提交证书申请" width="680px">
      <el-form :model="submitForm" label-width="150px">
        <el-form-item label="目标公司" required>
          <el-select v-model="submitCompany" placeholder="选择公司" style="width: 100%" @change="onSubmitCompanyChange">
            <el-option
                v-for="o in companyOptions"
                :key="o.uscc"
                :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                :value="o.uscc"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="申请人类别" required>
          <el-radio-group v-model="submitForm.applicantType">
            <el-radio-button value="INSTITUTION">单位</el-radio-button>
            <el-radio-button value="PERSON">个人</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="申请人/公司名称" required>
          <el-input v-model="submitForm.applicantName"
                    :placeholder="submitForm.applicantType === 'INSTITUTION' ? '如 医保前置系统/结算经办' : '如 张三'"/>
        </el-form-item>
        <el-form-item label="证书模板" required>
          <el-select v-model="submitForm.templateId" placeholder="选择证书模板" style="width: 100%">
            <el-option v-for="t in templates" :key="t.id" :label="`${t.templateName}（${t.templateCode}）`"
                       :value="t.id"/>
          </el-select>
        </el-form-item>
        <el-form-item label="主体CN" required>
          <el-input v-model="submitCn" placeholder="如 张三 / his.yibao.local"/>
        </el-form-item>
        <el-form-item label="主体O" required>
          <el-input v-model="submitO" disabled placeholder="取所选公司受控主体（自动）"/>
          <div class="dn-tip">O 由所选公司档案受控，禁手填防冒名。</div>
        </el-form-item>
        <el-form-item label="员工编号（OU）" required>
          <el-input v-model="submitOu" placeholder="必填：与 HIS 员工编号一致，同一公司内唯一（重名人员靠它区分）"/>
        </el-form-item>
        <el-form-item label="主体别名（SAN）">
          <el-input v-model="submitForm.san" placeholder="逗号分隔：DNS或IP"/>
        </el-form-item>
        <el-form-item label="公钥PEM" required>
          <el-input v-model="submitForm.publicKeyPem" :rows="5"
                    placeholder="申请方自持私钥，仅提交公钥（-----BEGIN PUBLIC KEY----- ...）"
                    type="textarea"/>
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="submitForm.description"/>
        </el-form-item>
        <el-form-item label="审批人" required>
          <el-select v-model="submitAssignee" placeholder="从流程节点筛选出的审批人中选择" style="width: 100%">
            <el-option
                v-for="u in assigneeOptions"
                :key="u.id"
                :label="`${u.realName}（${u.roleName }）`"
                :value="u.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="submitVisible = false">取消</el-button>
          <el-button :loading="submitting" type="primary" @click="onSubmit">提交</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 审核（批准/驳回） -->
    <el-dialog v-model="reviewVisible" :close-on-click-modal="false"
               :title="reviewAction === 'approve' ? '批准申请' : '驳回申请'"
               width="480px">
      <p style="margin-bottom: 12px">
        {{
          reviewAction === 'approve' ? '批准后该申请可进入签发环节（审核人不得与提交人同一人）。' : '驳回必须填写审核意见。'
        }}
      </p>
      <el-form label-width="90px">
        <el-form-item label="审核意见">
          <el-input v-model="reviewComment" :placeholder="reviewAction === 'approve' ? '可选' : '必填，如 材料不全'"
                    :rows="3"
                    type="textarea"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="reviewVisible = false">取消</el-button>
          <el-button :loading="submitting" :type="reviewAction === 'approve' ? 'success' : 'danger'"
                     @click="onReviewConfirm">
            {{ reviewAction === 'approve' ? '批准' : '驳回' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 操作单审批（批准=执行进 CRL；驳回=意见必填） -->
    <el-dialog v-model="opReviewVisible" :close-on-click-modal="false"
               :title="opReviewAction === 'approve' ? '批准操作单（批准即执行）' : '驳回操作单'"
               width="480px">
      <p style="margin-bottom: 12px">
        {{
          opReviewTarget ? `${opTypeText(opReviewTarget.opType)} ${opReviewTarget.certSubjectCn}（发起人 ${opReviewTarget.requestedByName || '无'}）` : ''
        }}
        <template v-if="opReviewTarget?.reason">{{ `操作原因：${reasonText(opReviewTarget.reason)}。` }}</template>
        <template v-if="opReviewTarget?.validityDays">{{ `新有效期：${opReviewTarget.validityDays} 天。` }}</template>
        {{
          opReviewAction === 'approve' ? '批准后立即执行：证书状态流转并进 CRL/OCSP，审批人不得与发起人同一人。' : '驳回必须填写审批意见，证书状态不变。'
        }}
      </p>
      <el-form label-width="90px">
        <el-form-item label="审批意见">
          <el-input v-model="opReviewComment"
                    :placeholder="opReviewAction === 'approve' ? '可填写审批说明' : '必填，如 原因不成立'" :rows="3"
                    type="textarea"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="opReviewVisible = false">取消</el-button>
          <el-button :loading="submitting" :type="opReviewAction === 'approve' ? 'success' : 'danger'"
                     @click="onOpReviewConfirm">
            {{ opReviewAction === 'approve' ? '批准并执行' : '驳回' }}
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 证书操作单详情（流程图 + 审批轨迹 + 催办） -->
    <el-dialog v-model="opDetailVisible" :close-on-click-modal="false" title="证书操作单详情" width="900px">
      <el-alert v-if="opDetail && opDetail.status === 'PENDING'" :closable="false" :title="opNextApproverTitle"
                show-icon
                style="margin-bottom: 12px" type="warning"/>
      <WorkflowDiagram :diagram="opDiagram" style="margin-bottom: 16px"/>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="操作单号">{{ opDetail?.opNo }}</el-descriptions-item>
        <el-descriptions-item label="操作类型">{{ opDetail ? opTypeText(opDetail.opType) : '' }}</el-descriptions-item>
        <el-descriptions-item label="证书主体">{{ opDetail?.certSubjectCn || '-' }}</el-descriptions-item>
        <el-descriptions-item label="序列号">
          <span class="cell-mono">{{ opDetail?.certSerialHex || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="所属公司">{{
            companyNames[opDetail?.companyUscc] || opDetail?.companyUscc || '-'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="操作原因">{{
            opDetail?.reason ? reasonText(opDetail.reason) : '无'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="新有效期">{{
            opDetail?.validityDays ? opDetail.validityDays + ' 天' : '无'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ opDetail ? opStatusText(opDetail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="发起人/时间">{{ opDetail?.requestedByName || '-' }}
          / {{ opDetail?.requestedAt }}
        </el-descriptions-item>
        <el-descriptions-item label="被指派审批人">{{
            opDetail?.assigneeRealName || '未指派（按角色流转）'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="审批人/时间">{{ opDetail?.reviewedByName || '-' }}
          / {{ opDetail?.reviewedAt || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="审批意见" :span="2">{{ opDetail?.reviewComment || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="trail-title">审批轨迹</div>
      <el-timeline v-if="opTrail.length" class="trail">
        <el-timeline-item v-for="t in opTrail" :key="t.id" :hollow="t.step === 'URGE'" :timestamp="t.createdAt"
                          :type="trailType(t.step)">
          <div class="trail-step">{{ trailStepText(t.step) }}</div>
          <div class="trail-who">{{ t.operatorRealName || '无' }}<span
              v-if="t.operatorRole">（{{ t.operatorRole }}）</span></div>
          <div v-if="t.comment" class="trail-comment">{{ t.comment }}</div>
        </el-timeline-item>
      </el-timeline>
      <template #footer>
        <div class="dialog-footer">
          <el-button v-if="opCanUrge(opDetail)" :loading="opUrging" type="warning" @click="onOpUrge(opDetail)">催办审批人
          </el-button>
          <el-button @click="opDetailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="申请单详情" width="900px">
      <el-alert v-if="detail && detail.status === 'PENDING'" :closable="false" :title="nextApproverTitle" show-icon
                style="margin-bottom: 12px" type="warning"/>
      <el-alert v-if="detail && detail.status === 'REJECTED'" :closable="false" show-icon
                style="margin-bottom: 12px" type="error"
                :title="`该申请已被驳回${detail.reviewedByName ? '（驳回人：' + detail.reviewedByName + '）' : ''}`"
                :description="detail.reviewComment || '未填写驳回意见'"/>
      <el-alert v-if="detail && detail.status === 'CANCELLED'" :closable="false" show-icon
                style="margin-bottom: 12px" type="info" title="该申请已被提交人撤销"/>
      <WorkflowDiagram :diagram="diagram" style="margin-bottom: 16px"/>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="申请单号">{{ detail?.applyNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="申请人类别">{{
            detail?.applicantType === 'INSTITUTION' ? '单位' : '个人'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="申请人">{{ detail?.applicantName }}</el-descriptions-item>
        <el-descriptions-item label="所属公司">{{
            companyNames[detail?.companyUscc] || detail?.companyUscc || '-'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="证书模板">{{
            detail ? templateName(detail.templateId) : ''
          }}
        </el-descriptions-item>
        <el-descriptions-item label="主体DN" :span="2">{{ detail?.subjectDn }}</el-descriptions-item>
        <el-descriptions-item label="主体别名（SAN）" :span="2">{{ detail?.san || '-' }}</el-descriptions-item>
        <el-descriptions-item label="公钥PEM" :span="2">
          <pre v-if="detail?.publicKeyPem" class="pem-block">{{ detail.publicKeyPem }}</pre>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="签发证书">
          {{ detail?.issuedCertId ? `#${detail.issuedCertId}（证书管理可查/下载）` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="trail-title">审批记录</div>
      <el-table v-if="detailTrail.length" :data="detailTrail" border size="small">
        <el-table-column label="环节" width="120">
          <template #default="{ row: t }">
            <el-tag :type="trailType(t.step)" size="small">{{ trailStepText(t.step) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="130">
          <template #default="{ row: t }">
            {{ t.operatorRealName || '—' }}
            <span v-if="t.operatorRole" class="trail-role">（{{ roleText(t.operatorRole) }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="处理时间" prop="createdAt" width="170"/>
        <el-table-column label="状态变化" width="150">
          <template #default="{ row: t }">
            {{ t.fromStatus ? `${statusText(t.fromStatus)} → ${statusText(t.toStatus)}` : statusText(t.toStatus) }}
          </template>
        </el-table-column>
        <el-table-column label="意见/备注" min-width="180">
          <template #default="{ row: t }">
            <span :class="{ 'trail-reject-text': t.step === 'REJECT' }">{{ t.comment || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <span v-else class="trail-empty">暂无审批记录</span>

      <template #footer>
        <div class="dialog-footer">
          <el-button v-if="canUrge(detail)" :loading="urging" type="warning" @click="onUrge(detail)">催办审批人
          </el-button>
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {ElMessage, ElMessageBox} from 'element-plus'
import {ArrowDown, Refresh, Search} from '@element-plus/icons-vue'
import {
  approveApplication,
  approveCertOp,
  cancelApplication,
  cancelCertOp,
  companyNameMap,
  detailApplication,
  detailCertOp,
  issueApplication,
  listCompaniesCached,
  listCertOpTrail,
  listFlowCandidates,
  listTrail,
  pageApplications,
  pageCertOps,
  pageTemplates,
  rejectApplication,
  rejectCertOp,
  submitApplication,
  urgeApplication,
  urgeCertOp
} from '@/api/system'
import {getWorkflowDiagram} from '@/api/workflow'
import WorkflowDiagram from '@/views/WorkflowDiagram.vue'
import {hasPerm} from '@/lib/perm'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'

const route = useRoute()
const router = useRouter()

// 路由决定页面形态：RA申请（申请人） / RA申请审核（审核人，合并申请单+操作单）
const isReviewMode = route.name === 'application-review'
// RA 证书申请流程编码（候选人与提交起流程都用它）
const FLOW_CODE = 'raApplyFlow'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const templates = ref([])
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, companyUscc: '', status: '', keyword: ''})

// RA申请审核：申请单 + 操作单合并成一张表（前端合并，按单据类型/状态本地筛选）
const reviewLoading = ref(false)
const reviewAll = ref([])
const reviewQuery = reactive({
  pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, docType: '', status: '', companyUscc: '', keyword: ''
})
const reviewFiltered = computed(() => reviewAll.value.filter(r =>
    (!reviewQuery.docType || r.docType === reviewQuery.docType)
    && (!reviewQuery.status || r.status === reviewQuery.status)
))
const reviewPageRows = computed(() => {
  const start = (reviewQuery.pageNo - 1) * reviewQuery.pageSize
  return reviewFiltered.value.slice(start, start + reviewQuery.pageSize)
})

// 提交表单：审批人（流程首个审批节点候选，单选）
const assigneeOptions = ref([])
const submitAssignee = ref(null)

const opReviewVisible = ref(false)
const opReviewAction = ref('approve')
const opReviewComment = ref('')
const opReviewTarget = ref(null)

// 证书操作单详情（流程图 + 审批轨迹 + 催办）
const opDetailVisible = ref(false)
const opDetail = ref(null)
const opDiagram = ref(null)
const opTrail = ref([])
const opUrging = ref(false)
const opNextApproverTitle = computed(() => {
  const d = opDetail.value
  if (!d) return ''
  if (d.status === 'PENDING') {
    return d.assigneeRealName
        ? `当前环节：等待【${d.assigneeRealName}】审批`
        : '当前环节：等待【审核员】审批（未指定具体人）'
  }
  if (d.status === 'EXECUTED') return '当前环节：已批准并执行'
  if (d.status === 'REJECTED') return '当前环节：已驳回'
  if (d.status === 'CANCELLED') return '当前环节：已撤销'
  return ''
})

// 按钮权限点与后端接口校验一一对应；旧会话（无权限缓存）回退角色判断，服务端仍强校验
const username = localStorage.getItem('username') || ''
const canOpApprove = hasPerm('cert:op:approve')

const reasonMap = {
  KEY_COMPROMISE: '密钥泄露',
  CA_COMPROMISE: 'CA泄露',
  AFFILIATION_CHANGED: '隶属关系变更',
  SUPERSEDED: '被新证书替代',
  CESSATION_OF_OPERATION: '停止运营',
  CERTIFICATE_HOLD: '证书挂起',
  PRIVILEGE_WITHDRAWN: '权限撤销',
  UNSPECIFIED: '未指定'
}

function reasonText(r) {
  return reasonMap[r] || r || '未指定'
}

function opTypeText(t) {
  return {REVOKE: '吊销', SUSPEND: '挂起', UNSUSPEND: '解除挂起', RENEW: '证书更新', REKEY: '密钥更新'}[t] || t
}

function opTypeTag(t) {
  return {REVOKE: 'danger', SUSPEND: 'warning', UNSUSPEND: 'success', RENEW: 'primary', REKEY: 'primary'}[t] || ''
}

function opStatusText(s) {
  return {PENDING: '待审批', EXECUTED: '已执行', REJECTED: '已驳回', CANCELLED: '已撤销'}[s] || s
}

function opStatusTag(s) {
  return {PENDING: 'warning', EXECUTED: 'success', REJECTED: 'danger', CANCELLED: 'info'}[s] || ''
}

// 两卡式列表页：表格只设最大高度，超高内部滚动
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

// 公司域由页面显式选择：列表可留空=跨公司只读，提交/审批/签发票据按行归属公司
const companyOptions = ref([])
const companyNames = ref({})
const submitCompany = ref('')
// 主体 DN 的 O 由受理公司受控（前端拼 DN；subjectO 从公司注册表取，禁手填防冒名）；
// OU 选填，口径=员工编号等不变信息，科室等易变属性不进 DN
const submitCn = ref('')
const submitO = ref('')
const submitOu = ref('')

const submitVisible = ref(false)
const reviewVisible = ref(false)
const detailVisible = ref(false)
const reviewAction = ref('approve')
const reviewComment = ref('')
const detail = ref(null)
const detailTrail = ref([])
const diagram = ref(null)
const urging = ref(false)
const submitting = ref(false)

const submitForm = reactive({
  applicantType: 'PERSON', applicantName: '', templateId: null,
  subjectDn: '', san: '', publicKeyPem: '', description: ''
})

function templateName(id) {
  return templates.value.find(t => t.id === id)?.templateName || `#${id}`
}

function statusText(s) {
  return {PENDING: '待审核', APPROVED: '已批准', REJECTED: '已驳回', ISSUED: '已签发', CANCELLED: '已撤销'}[s] || s
}

function statusTag(s) {
  return {PENDING: 'warning', APPROVED: '', REJECTED: 'danger', ISSUED: 'success', CANCELLED: 'info'}[s] || ''
}

async function loadTemplates() {
  const res = await pageTemplates({pageNo: 1, pageSize: 100})
  templates.value = res.data.records || []
}

async function load() {
  loading.value = true
  try {
    const res = await pageApplications({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      companyUscc: query.companyUscc || undefined,
      status: query.status || undefined,
      keyword: query.keyword || undefined
    })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNo = 1
  load()
}

function handleReset() {
  query.companyUscc = ''
  query.status = ''
  query.keyword = ''
  handleSearch()
}

function handleSizeChange(val) {
  query.pageSize = val
  query.pageNo = 1
  load()
}

function handleCurrentChange(val) {
  query.pageNo = val
  load()
}

function submitSubjectOOf(uscc) {
  return companyOptions.value.find(o => o.uscc === uscc)?.subjectO || ''
}

function onSubmitCompanyChange() {
  submitO.value = submitSubjectOOf(submitCompany.value)
}

function openSubmit() {
  Object.assign(submitForm, {
    applicantType: 'PERSON', applicantName: '', templateId: null,
    subjectDn: '', san: '', publicKeyPem: '', description: ''
  })
  submitCompany.value = query.companyUscc
      || companyOptions.value.find(o => o.status === 'ACTIVE')?.uscc
      || companyOptions.value[0]?.uscc || ''
  submitCn.value = ''
  submitO.value = submitSubjectOOf(submitCompany.value)
  submitOu.value = ''
  submitAssignee.value = null
  assigneeOptions.value = []
  submitVisible.value = true
  listFlowCandidates(FLOW_CODE).then(res => {
    assigneeOptions.value = res.data || []
  }).catch(() => {
  })
}

async function onSubmit() {
  const cn = submitCn.value.trim()
  const o = submitO.value.trim()
  const ou = submitOu.value.trim()
  if (!submitCompany.value) {
    ElMessage.warning('请选择目标公司')
    return
  }
  if (!cn || !o) {
    ElMessage.warning('请填写主体CN（主体O 取所选公司受控主体）')
    return
  }
  if (!ou) {
    ElMessage.warning('请填写员工编号（OU）：同一公司内唯一，重名人员靠它区分')
    return
  }
  submitForm.subjectDn = `CN=${cn},OU=${ou},O=${o}`
  if (!submitForm.applicantName.trim() || !submitForm.subjectDn.trim() || !submitForm.publicKeyPem.trim() || !submitForm.templateId) {
    ElMessage.warning('请完整填写申请信息（含公钥PEM）')
    return
  }
  if (!submitAssignee.value) {
    ElMessage.warning('请选择审批人（候选范围由流程节点配置决定）')
    return
  }
  submitting.value = true
  try {
    await submitApplication({...submitForm, assigneeUserId: submitAssignee.value}, submitCompany.value)
    ElMessage.success('申请已提交，等待审核员审批')
    submitVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

function openReview(row, action) {
  reviewAction.value = action
  reviewComment.value = ''
  reviewTarget = row
  reviewVisible.value = true
}

let reviewTarget = null

async function onReviewConfirm() {
  if (reviewAction.value === 'reject' && !reviewComment.value.trim()) {
    ElMessage.warning('驳回必须填写审核意见')
    return
  }
  submitting.value = true
  try {
    if (reviewAction.value === 'approve') {
      await approveApplication(reviewTarget.id, reviewComment.value, reviewTarget.companyUscc)
      ElMessage.success('已批准，可进入签发环节')
    } else {
      await rejectApplication(reviewTarget.id, reviewComment.value, reviewTarget.companyUscc)
      ElMessage.success('已驳回')
    }
    reviewVisible.value = false
    refreshCurrentList()
  } finally {
    submitting.value = false
  }
}

async function onIssue(row) {
  await ElMessageBox.confirm(`按申请单 ${row.applyNo} 签发证书？私钥由申请方自持，CA 不落盘。`, '提示', {type: 'warning'})
  submitting.value = true
  try {
    const res = await issueApplication(row.id, row.companyUscc)
    ElMessage.success(`签发成功，证书 id=${res.data.id}（序列号 ${res.data.serialHex}）`)
    refreshCurrentList()
  } finally {
    submitting.value = false
  }
}

async function onCancel(row) {
  await ElMessageBox.confirm(`确定撤销申请单 ${row.applyNo} 吗？`, '提示', {type: 'warning'})
  await cancelApplication(row.id, row.companyUscc)
  ElMessage.success('申请已撤销')
  refreshCurrentList()
}

function onDetail(row) {
  detail.value = row
  detailVisible.value = true
  diagram.value = null
  detailTrail.value = []
  try {
    getWorkflowDiagram('CERT_APPLICATION', row.status, row.assigneeRealName).then(res => {
      diagram.value = res.data || null
    })
  } catch (e) {
  }
  // 详情页按完整数据展示（列表行是轻量快照），并加载审批记录（支持未来多层审批多条留痕）
  detailApplication(row.id).then(res => {
    if (res?.data) detail.value = res.data
  }).catch(() => {})
  listTrail(row.id).then(res => {
    // 审批记录只留业务审批动作；催办属提醒，不计入留痕列表
    detailTrail.value = (res?.data || []).filter(t => t.step !== 'URGE')
  }).catch(() => {})
}

async function onUrge(row) {
  if (!row) return
  await ElMessageBox.confirm(`向${row.assigneeRealName ? '审批人 ' + row.assigneeRealName : '本公司审核员'}发送催办提醒？`, '催办', {type: 'warning'})
  urging.value = true
  try {
    await urgeApplication(row.id, row.companyUscc)
    ElMessage.success('已发送催办提醒')
  } finally {
    urging.value = false
  }
}

function canUrge(row) {
  // 可否催办由服务端逐行回算（待审核且已指派审批人）；审批人登录账号不再随出参下发
  return !!row && !!row.urgeable && hasPerm('app:urge')
}

const nextApproverTitle = computed(() => {
  const d = detail.value
  if (!d) return ''
  if (d.status === 'PENDING') {
    return d.assigneeRealName
        ? `当前环节：等待【${d.assigneeRealName}】审批`
        : '当前环节：等待【审核员 / RA业务管理员】审批（未指定具体人）'
  }
  if (d.status === 'APPROVED') return '当前环节：已批准，等待操作员签发'
  if (d.status === 'ISSUED') return '当前环节：已签发'
  if (d.status === 'REJECTED') return '当前环节：已驳回'
  if (d.status === 'CANCELLED') return '当前环节：已撤销'
  return ''
})

function trailStepText(step) {
  return {
    SUBMIT: '提交申请',
    ASSIGN: '指定审批人',
    APPROVE: '审核批准',
    REJECT: '审核驳回',
    ISSUE: '签发证书',
    CANCEL: '撤销申请',
    URGE: '催办'
  }[step] || step
}

function trailType(step) {
  return {
    SUBMIT: 'primary',
    ASSIGN: 'info',
    APPROVE: 'success',
    REJECT: 'danger',
    ISSUE: 'success',
    CANCEL: 'info',
    URGE: 'warning'
  }[step] || ''
}

/** 角色编码→中文名（审批记录中展示处理人角色） */
const ROLE_TEXT = {
  ADMIN: '系统管理员',
  OPERATOR: 'CA操作员',
  APPROVER: '审核员',
  AUDITOR: '审计员',
  SECURITY_ADMIN: '安全管理员',
  KEY_ADMIN: '密钥管理员',
  COMPANY_ADMIN: '公司管理员',
  COMPANY_APPROVER: '公司审批员',
  USER_ADMIN: '用户与角色管理员',
  RA_ADMIN: 'RA业务管理员',
  CERT_OFFICER: '证书签发操作员',
  AUDIT_ADMIN: '审计管理员'
}

function roleText(code) {
  return ROLE_TEXT[code] || code || ''
}

// 操作列口径：前 3 个平铺，其余收进「更多」下拉（列表页统一模式）
const ACTION_VISIBLE = 3

function appActions(row) {
  const acts = []
  // reviewable 由后端按「本单被指派人 == 当前登录人」现算，与 approve/reject 接口同一口径；不可审的直接不给按钮
  if (row.status === 'PENDING' && row.reviewable) {
    acts.push({label: '批准', type: 'success', run: () => openReview(row, 'approve')})
  }
  if (row.status === 'PENDING' && row.reviewable) {
    acts.push({label: '驳回', type: 'danger', run: () => openReview(row, 'reject')})
  }
  if (row.status === 'APPROVED' && hasPerm('app:issue')) {
    acts.push({label: '签发', run: () => onIssue(row)})
  }
  acts.push({label: '详情', run: () => onDetail(row)})
  if (row.status === 'PENDING' && row.urgeable && hasPerm('app:urge')) {
    acts.push({label: '催办', type: 'warning', run: () => onUrge(row)})
  }
  if (['PENDING', 'APPROVED'].includes(row.status) && hasPerm('app:cancel')) {
    acts.push({label: '撤销', run: () => onCancel(row)})
  }
  return acts
}

function visibleRowActions(row) {
  return appActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return appActions(row).slice(ACTION_VISIBLE)
}

// ===== RA申请审核：申请单 + 证书操作单合并列表 =====

const REVIEW_FETCH_SIZE = 500

async function loadReview() {
  reviewLoading.value = true
  try {
    const params = {
      pageNo: 1,
      pageSize: REVIEW_FETCH_SIZE,
      companyUscc: reviewQuery.companyUscc || undefined,
      keyword: reviewQuery.keyword || undefined
    }
    const [appRes, opRes] = await Promise.all([pageApplications(params), pageCertOps(params)])
    const appItems = (appRes.data.records || []).map(a => ({
      key: `A-${a.id}`,
      docType: 'APPLICATION',
      raw: a,
      docNo: a.applyNo,
      summary: `${a.applicantName || '-'} · ${a.applicantType === 'INSTITUTION' ? '单位' : '个人'} · ${a.subjectDn || ''}`,
      companyUscc: a.companyUscc,
      requestedByName: a.appliedByName || '-',
      assigneeRealName: a.assigneeRealName || '',
      status: a.status,
      statusText: statusText(a.status),
      statusTag: statusTag(a.status),
      submittedAt: a.appliedAt || ''
    }))
    const opItems = (opRes.data.records || []).map(o => ({
      key: `O-${o.id}`,
      docType: 'OP_TICKET',
      raw: o,
      docNo: o.opNo,
      summary: `${opTypeText(o.opType)} · ${o.certSubjectCn || '-'} · ${o.reason ? reasonText(o.reason) : '未填写原因'}`,
      companyUscc: o.companyUscc,
      requestedByName: o.requestedByName || '-',
      assigneeRealName: o.assigneeRealName || '',
      status: o.status,
      statusText: opStatusText(o.status),
      statusTag: opStatusTag(o.status),
      submittedAt: o.createdAt || ''
    }))
    reviewAll.value = [...appItems, ...opItems]
        .sort((x, y) => String(y.submittedAt).localeCompare(String(x.submittedAt)))
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    reviewLoading.value = false
  }
}

function handleReviewSearch() {
  reviewQuery.pageNo = 1
  loadReview()
}

function handleReviewReset() {
  reviewQuery.docType = ''
  reviewQuery.status = ''
  reviewQuery.companyUscc = ''
  reviewQuery.keyword = ''
  handleReviewSearch()
}

/** 合并列表的操作列：按单据类型复用各自的按钮口径 */
function reviewRowActions(row) {
  return row.docType === 'APPLICATION' ? appActions(row.raw) : opActions(row.raw)
}

/** 处理完单据后刷新当前页面对应的列表 */
function refreshCurrentList() {
  if (isReviewMode) loadReview()
  else load()
}

// 撤销=发起人本人（后端硬校验，前端仅按可判条件显示）
// reviewable 由后端按「本单被指派人 == 当前登录人」现算，与 approve/reject 接口同一口径；不可审的直接不给按钮
function opActions(row) {
  const acts = []
  if (row.status === 'PENDING' && row.reviewable) {
    acts.push({label: '批准', type: 'success', run: () => openOpReview(row, 'approve')})
  }
  if (row.status === 'PENDING' && row.reviewable) {
    acts.push({label: '驳回', type: 'danger', run: () => openOpReview(row, 'reject')})
  }
  acts.push({label: '详情', run: () => onOpDetail(row)})
  if (row.status === 'PENDING' && row.urgeable && hasPerm('app:urge')) {
    acts.push({label: '催办', type: 'warning', run: () => onOpUrge(row)})
  }
  if (row.revocable) {
    acts.push({label: '撤销', run: () => onOpCancel(row)})
  }
  return acts
}

function openOpReview(row, action) {
  opReviewAction.value = action
  opReviewComment.value = ''
  opReviewTarget.value = row
  opReviewVisible.value = true
}

async function onOpReviewConfirm() {
  if (opReviewAction.value === 'reject' && !opReviewComment.value.trim()) {
    ElMessage.warning('驳回必须填写审批意见')
    return
  }
  submitting.value = true
  try {
    if (opReviewAction.value === 'approve') {
      await approveCertOp(opReviewTarget.value.id, opReviewComment.value, opReviewTarget.value.companyUscc)
      ElMessage.success('已批准并执行（证书状态已流转，CRL/OCSP 同步生效）')
    } else {
      await rejectCertOp(opReviewTarget.value.id, opReviewComment.value, opReviewTarget.value.companyUscc)
      ElMessage.success('已驳回')
    }
    opReviewVisible.value = false
    refreshCurrentList()
  } finally {
    submitting.value = false
  }
}

async function onOpCancel(row) {
  await ElMessageBox.confirm(`确定撤销操作单 ${row.opNo} 吗？`, '提示', {type: 'warning'})
  await cancelCertOp(row.id, row.companyUscc)
  ElMessage.success('操作单已撤销')
  refreshCurrentList()
}

async function onOpDetail(row) {
  opDetail.value = row
  opDetailVisible.value = true
  opTrail.value = []
  opDiagram.value = null
  try {
    listCertOpTrail(row.id).then(res => {
      opTrail.value = res.data || []
    })
    getWorkflowDiagram('CERT_OP', row.status, row.assigneeRealName).then(res => {
      opDiagram.value = res.data || null
    })
  } catch (e) {
  }
}

async function onOpUrge(row) {
  if (!row) return
  await ElMessageBox.confirm(`向${row.assigneeRealName ? '审批人 ' + row.assigneeRealName : '审核员'}发送催办提醒？`, '催办', {type: 'warning'})
  opUrging.value = true
  try {
    await urgeCertOp(row.id, row.companyUscc)
    ElMessage.success('已发送催办提醒')
    const res = await listCertOpTrail(row.id)
    opTrail.value = res.data || []
  } finally {
    opUrging.value = false
  }
}

function opCanUrge(row) {
  // 可否催办由服务端逐行回算（待审批且已指派审批人）；审批人登录账号不再随出参下发
  return !!row && !!row.urgeable && hasPerm('app:urge')
}

onMounted(async () => {
  loadTemplates()
  companyOptions.value = await listCompaniesCached()
  companyNames.value = await companyNameMap()
  if (isReviewMode) {
    await loadReview()
  } else {
    load()
  }
  await handleDeepLink()
})

/**
 * 深链直达（我的待办聚合页跳转）：?applyId=xx 打开 RA 申请单详情，?certOpId=xx 打开证书操作单详情。
 * 打开后清掉 query，避免刷新时重复弹框。
 */
async function handleDeepLink() {
  const applyId = route.query.applyId
  const certOpId = route.query.certOpId
  if (!applyId && !certOpId) return
  if (applyId) {
    try {
      const res = await detailApplication(applyId)
      onDetail(res.data)
    } catch (e) {
      // 单据不存在或无权查看时留在列表
    }
  }
  if (certOpId) {
    try {
      const res = await detailCertOp(certOpId)
      await onOpDetail(res.data)
    } catch (e) {
      // 单据不存在或无权查看时留在列表
    }
  }
  router.replace({path: route.path})
}
</script>

<style scoped>
.crl-tabs :deep(.el-tabs__header) {
  background: #fff;
  border-radius: 6px;
  padding: 0 16px;
  margin-bottom: 12px;
}

.dn-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.pem-block {
  margin: 0;
  max-height: 120px;
  overflow: auto;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.trail-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.trail :deep(.el-timeline-item__timestamp) {
  color: #909399;
  font-size: 12px;
}

.trail-step {
  font-weight: 600;
  color: #303133;
}

.trail-who {
  font-size: 13px;
  color: #606266;
  margin-top: 2px;
}

.trail-comment {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}

.trail-role {
  color: #909399;
  font-size: 12px;
}

.trail-reject-text {
  color: #f56c6c;
}

.trail-empty {
  color: #909399;
  font-size: 13px;
  padding: 8px 0 4px;
}

.empty-tip {
  color: #909399;
  font-size: 13px;
  padding: 8px 0 4px;
}
</style>
