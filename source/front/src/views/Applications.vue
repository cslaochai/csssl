<template>
  <div>
    <el-tabs v-model="activeTab" class="crl-tabs" @tab-change="onTabChange">
      <!-- 证书申请（RA 提交-审核-签发） -->
      <el-tab-pane label="证书申请" name="apply">
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
      </el-tab-pane>

      <!-- 证书操作审批（吊销/挂起/解除挂起双人复核，批准即执行进 CRL） -->
      <el-tab-pane label="证书操作审批" name="certops">
        <el-card class="query-card" shadow="never">
          <div class="query-wrap">
            <el-form :model="opQuery" inline>
              <el-form-item label="公司">
                <el-select v-model="opQuery.companyUscc" clearable placeholder="全部" style="width: 280px"
                           @change="handleOpSearch">
                  <el-option
                      v-for="o in companyOptions"
                      :key="o.uscc"
                      :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                      :value="o.uscc"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="状态">
                <el-select v-model="opQuery.status" clearable placeholder="全部" style="width: 130px"
                           @change="handleOpSearch">
                  <el-option label="待审批" value="PENDING"/>
                  <el-option label="已执行" value="EXECUTED"/>
                  <el-option label="已驳回" value="REJECTED"/>
                  <el-option label="已撤销" value="CANCELLED"/>
                </el-select>
              </el-form-item>
              <el-form-item label="操作类型">
                <el-select v-model="opQuery.opType" clearable placeholder="全部" style="width: 130px"
                           @change="handleOpSearch">
                  <el-option label="吊销" value="REVOKE"/>
                  <el-option label="挂起" value="SUSPEND"/>
                  <el-option label="解除挂起" value="UNSUSPEND"/>
                  <el-option label="证书更新" value="RENEW"/>
                  <el-option label="密钥更新" value="REKEY"/>
                </el-select>
              </el-form-item>
              <el-form-item label="关键字">
                <el-input
                    v-model="opQuery.keyword"
                    clearable
                    placeholder="主体CN / 序列号 / 单号"
                    style="width: 220px"
                    @clear="handleOpSearch"
                    @keyup.enter="handleOpSearch"
                />
              </el-form-item>
              <el-form-item>
                <el-button :icon="Search" type="primary" @click="handleOpSearch">搜索</el-button>
                <el-button :icon="Refresh" @click="handleOpReset">重置</el-button>
              </el-form-item>
            </el-form>
            <div class="query-actions"></div>
          </div>
        </el-card>

        <el-card class="table-card" shadow="never">
          <el-table v-loading="opLoading" :data="opRows" :max-height="tableMaxHeight" stripe style="width: 100%">
            <el-table-column class-name="cell-mono" label="操作单号" prop="opNo" show-overflow-tooltip width="180"/>
            <el-table-column label="操作类型" width="100">
              <template #default="{ row }">
                <el-tag :type="opTypeTag(row.opType)" size="small">{{ opTypeText(row.opType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="证书主体" min-width="160" prop="certSubjectCn" show-overflow-tooltip/>
            <el-table-column label="所属公司" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ companyNames[row.companyUscc] || row.companyUscc || '-' }}</template>
            </el-table-column>
            <el-table-column class-name="cell-mono" label="序列号" min-width="150" prop="certSerialHex"
                             show-overflow-tooltip/>
            <el-table-column label="操作原因" min-width="130">
              <template #default="{ row }">{{ row.reason ? reasonText(row.reason) : '无' }}</template>
            </el-table-column>
            <el-table-column label="新有效期" width="90">
              <template #default="{ row }">{{ row.validityDays ? row.validityDays + ' 天' : '无' }}</template>
            </el-table-column>
            <el-table-column label="发起人" width="130">
              <template #default="{ row }">{{ row.requestedByName }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="opStatusTag(row.status)" size="small">{{ opStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="被指派审批人" width="130">
              <template #default="{ row }">{{ row.assigneeRealName || '未指派' }}</template>
            </el-table-column>
            <el-table-column label="审批人" width="130">
              <template #default="{ row }">{{ row.reviewedByName }}</template>
            </el-table-column>
            <el-table-column align="right" fixed="right" label="操作" width="240">
              <template #default="{ row }">
                <el-button
                    v-for="a in visibleOpActions(row)"
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
                v-model:current-page="opQuery.pageNo"
                v-model:page-size="opQuery.pageSize"
                :page-sizes="PAGE_SIZES"
                :total="opTotal"
                layout="total, sizes, prev, pager, next, jumper"
                @size-change="handleOpSizeChange"
                @current-change="handleOpCurrentChange"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

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
        <el-form-item label="主体OU">
          <el-input v-model="submitOu" placeholder="选填：员工编号等不变信息（科室等易变属性勿填）"/>
        </el-form-item>
        <el-form-item label="主体别名（SAN）">
          <el-input v-model="submitForm.san" placeholder="逗号分隔：DNS或IP"/>
        </el-form-item>
        <el-form-item label="公钥PEM" required>
          <el-input v-model="submitForm.publicKeyPem" :rows="5" placeholder="申请方自持私钥，仅提交公钥（-----BEGIN PUBLIC KEY----- ...）"
                    type="textarea"/>
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="submitForm.description"/>
        </el-form-item>
        <el-form-item label="指定审批人" required>
          <el-select v-model="submitAssignee" :loading="approversLoading"
                     placeholder="必选：本单只能由被指派的审批人审核" style="width: 100%">
            <el-option v-for="a in approverOptions" :key="a.username"
                       :label="a.realName ? `${a.realName}（${a.username}）` : a.username" :value="a.username"/>
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
    <el-dialog v-model="reviewVisible" :close-on-click-modal="false" :title="reviewAction === 'approve' ? '批准申请' : '驳回申请'"
               width="480px">
      <p style="margin-bottom: 12px">
        {{
          reviewAction === 'approve' ? '批准后该申请可进入签发环节（审核人不得与提交人同一人）。' : '驳回必须填写审核意见。'
        }}
      </p>
      <el-form label-width="90px">
        <el-form-item label="审核意见">
          <el-input v-model="reviewComment" :placeholder="reviewAction === 'approve' ? '可选' : '必填，如 材料不全'" :rows="3"
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
          <el-input v-model="opReviewComment" :placeholder="opReviewAction === 'approve' ? '可填写审批说明' : '必填，如 原因不成立'" :rows="3"
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
    <el-dialog v-model="opDetailVisible" :close-on-click-modal="false" title="证书操作单详情" width="640px">
      <el-alert v-if="opDetail && opDetail.status === 'PENDING'" :closable="false" :title="opNextApproverTitle" show-icon
                style="margin-bottom: 12px" type="warning"/>
      <WorkflowDiagram :diagram="opDiagram" style="margin-bottom: 16px"/>
      <el-descriptions :column="1" border>
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
        <el-descriptions-item label="操作原因">{{ opDetail?.reason ? reasonText(opDetail.reason) : '无' }}</el-descriptions-item>
        <el-descriptions-item label="新有效期">{{ opDetail?.validityDays ? opDetail.validityDays + ' 天' : '无' }}</el-descriptions-item>
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
        <el-descriptions-item label="审批意见">{{ opDetail?.reviewComment || '-' }}</el-descriptions-item>
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
    <el-dialog v-model="detailVisible" title="申请单详情" width="620px">
      <el-alert v-if="detail && detail.status === 'PENDING'" :closable="false" :title="nextApproverTitle" show-icon
                style="margin-bottom: 12px" type="warning"/>
      <WorkflowDiagram :diagram="diagram" style="margin-bottom: 16px"/>
      <el-descriptions :column="1" border>
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
        <el-descriptions-item label="主体DN">{{ detail?.subjectDn }}</el-descriptions-item>
        <el-descriptions-item label="主体别名（SAN）">{{ detail?.san || '-' }}</el-descriptions-item>
        <el-descriptions-item label="公钥PEM">
          <pre v-if="detail?.publicKeyPem" class="pem-block">{{ detail.publicKeyPem }}</pre>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="签发证书">
          {{ detail?.issuedCertId ? `#${detail.issuedCertId}（证书管理可查/下载）` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="提交人/时间">{{ detail?.appliedByName || '-' }}
          / {{ detail?.appliedAt }}
        </el-descriptions-item>
        <el-descriptions-item label="审核人/时间">{{
            detail?.reviewedByName || '-'
          }} / {{ detail?.reviewedAt || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="审核意见">{{ detail?.reviewComment || '-' }}</el-descriptions-item>
        <el-descriptions-item label="指定审批人">{{
            detail?.assigneeRealName || '未指定（按角色流转）'
          }}
        </el-descriptions-item>
      </el-descriptions>
      <div class="trail-title">审批轨迹</div>
      <el-timeline v-if="trail.length" class="trail">
        <el-timeline-item v-for="t in trail" :key="t.id" :hollow="t.step === 'URGE'" :timestamp="t.createdAt"
                          :type="trailType(t.step)">
          <div class="trail-step">{{ trailStepText(t.step) }}</div>
          <div class="trail-who">{{ t.operatorRealName || '无' }}<span
              v-if="t.operatorRole">（{{ t.operatorRole }}）</span></div>
          <div v-if="t.comment" class="trail-comment">{{ t.comment }}</div>
        </el-timeline-item>
      </el-timeline>
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
  listApprovers,
  listCompaniesCached,
  listCertOpTrail,
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

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const templates = ref([])
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, companyUscc: '', status: '', keyword: ''})

// 页签：证书申请 / 证书操作审批
const activeTab = ref('apply')

// 操作单列表状态（独立分页与筛选，切页签时加载）
const opLoading = ref(false)
const opRows = ref([])
const opTotal = ref(0)
const opQuery = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, companyUscc: '', status: '', opType: '', keyword: ''})
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
const trail = ref([])
const diagram = ref(null)
const urging = ref(false)
const approvers = ref([])
const approversLoading = ref(false)
const submitAssignee = ref('')
// 提交人不得自审（后端同口径拦），不合格候选直接从下拉里去掉而不是置灰
const approverOptions = computed(() => approvers.value.filter(a => a.username !== username))
const submitting = ref(false)

const submitForm = reactive({
  applicantType: 'PERSON', applicantName: '', templateId: null,
  subjectDn: '', san: '', publicKeyPem: '', description: '',
  assigneeUsername: null, assigneeRealName: null
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
  loadApprovers()
}

function openSubmit() {
  Object.assign(submitForm, {
    applicantType: 'PERSON', applicantName: '', templateId: null,
    subjectDn: '', san: '', publicKeyPem: '', description: '',
    assigneeUsername: null, assigneeRealName: null
  })
  submitCompany.value = query.companyUscc
      || companyOptions.value.find(o => o.status === 'ACTIVE')?.uscc
      || companyOptions.value[0]?.uscc || ''
  submitCn.value = ''
  submitO.value = submitSubjectOOf(submitCompany.value)
  submitOu.value = ''
  // 预选公司是代码赋值，不会触发 el-select 的 @change，需主动拉取审批人列表
  loadApprovers()
  submitVisible.value = true
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
  submitForm.subjectDn = `CN=${cn},O=${o}` + (ou ? `,OU=${ou}` : '')
  if (!submitForm.applicantName.trim() || !submitForm.subjectDn.trim() || !submitForm.publicKeyPem.trim() || !submitForm.templateId) {
    ElMessage.warning('请完整填写申请信息（含公钥PEM）')
    return
  }
  if (!submitAssignee.value) {
    ElMessage.warning('请选择审批人（本单只能由被指派的审批人审核）')
    return
  }
  submitForm.assigneeUsername = submitAssignee.value
  submitForm.assigneeRealName = approvers.value.find(a => a.username === submitAssignee.value)?.realName || null
  submitting.value = true
  try {
    await submitApplication({...submitForm}, submitCompany.value)
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
    load()
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
    load()
  } finally {
    submitting.value = false
  }
}

async function onCancel(row) {
  await ElMessageBox.confirm(`确定撤销申请单 ${row.applyNo} 吗？`, '提示', {type: 'warning'})
  await cancelApplication(row.id, row.companyUscc)
  ElMessage.success('申请已撤销')
  load()
}

function onDetail(row) {
  detail.value = row
  detailVisible.value = true
  trail.value = []
  diagram.value = null
  try {
    listTrail(row.id).then(res => {
      trail.value = res.data || []
    })
    getWorkflowDiagram('CERT_APPLICATION', row.status, row.assigneeRealName).then(res => {
      diagram.value = res.data || null
    })
  } catch (e) {
  }
}

async function loadApprovers() {
  // 审批人池为平台级（不随受理公司绑定），无需传 companyUscc，后端按登录人公司安全收窄即可
  approversLoading.value = true
  try {
    const res = await listApprovers()
    approvers.value = res.data || []
  } catch (e) {
  } finally {
    approversLoading.value = false
  }
}

async function onUrge(row) {
  if (!row) return
  await ElMessageBox.confirm(`向${row.assigneeRealName ? '审批人 ' + row.assigneeRealName : '本公司审核员'}发送催办提醒？`, '催办', {type: 'warning'})
  urging.value = true
  try {
    await urgeApplication(row.id, row.companyUscc)
    ElMessage.success('已发送催办提醒')
    const res = await listTrail(row.id)
    trail.value = res.data || []
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

// ===== 证书操作审批页签 =====

function onTabChange(name) {
  if (name === 'certops') {
    loadOps()
  }
}

async function loadOps() {
  opLoading.value = true
  try {
    const res = await pageCertOps({
      pageNo: opQuery.pageNo,
      pageSize: opQuery.pageSize,
      companyUscc: opQuery.companyUscc || undefined,
      status: opQuery.status || undefined,
      opType: opQuery.opType || undefined,
      keyword: opQuery.keyword || undefined
    })
    opRows.value = res.data.records || []
    opTotal.value = res.data.total || 0
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    opLoading.value = false
  }
}

function handleOpSearch() {
  opQuery.pageNo = 1
  loadOps()
}

function handleOpReset() {
  opQuery.companyUscc = ''
  opQuery.status = ''
  opQuery.opType = ''
  opQuery.keyword = ''
  handleOpSearch()
}

function handleOpSizeChange(val) {
  opQuery.pageSize = val
  opQuery.pageNo = 1
  loadOps()
}

function handleOpCurrentChange(val) {
  opQuery.pageNo = val
  loadOps()
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

function visibleOpActions(row) {
  return opActions(row).slice(0, ACTION_VISIBLE)
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
    loadOps()
  } finally {
    submitting.value = false
  }
}

async function onOpCancel(row) {
  await ElMessageBox.confirm(`确定撤销操作单 ${row.opNo} 吗？`, '提示', {type: 'warning'})
  await cancelCertOp(row.id, row.companyUscc)
  ElMessage.success('操作单已撤销')
  loadOps()
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
  load()
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
    activeTab.value = 'certops'
    await loadOps()
    try {
      const res = await detailCertOp(certOpId)
      await onOpDetail(res.data)
    } catch (e) {
      // 单据不存在或无权查看时留在列表
    }
  }
  router.replace({path: '/applications'})
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

.empty-tip {
  color: #909399;
  font-size: 13px;
  padding: 8px 0 4px;
}
</style>
