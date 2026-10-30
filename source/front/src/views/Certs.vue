<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="证书类型">
            <el-select v-model="query.certType" clearable placeholder="全部" style="width: 130px" @change="handleSearch">
              <el-option label="服务端" value="SERVER" />
              <el-option label="客户端" value="CLIENT" />
              <el-option label="二级CA" value="SUB_CA" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px" @change="handleSearch">
              <el-option label="有效" value="VALID" />
              <el-option label="已吊销" value="REVOKED" />
              <el-option label="已挂起" value="FROZEN" />
              <el-option label="已过期" value="EXPIRED" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input
              v-model="query.keyword"
              placeholder="主体CN / 序列号"
              clearable
              style="width: 220px"
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </el-form-item>
        </el-form>
        <div class="query-actions">
          <el-tag size="small" type="info" effect="plain">当前域：{{ tenantName || tenantDomain }}</el-tag>
          <template v-if="canIssue">
            <el-button v-perm="'cert:issue'" type="primary" @click="openIssue">签发证书</el-button>
            <el-button v-perm="'cert:issue'" @click="openCsr">CSR签发</el-button>
            <el-button v-perm="'cert:issue'" @click="openPubKey">公钥直签</el-button>
          </template>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="subjectCn" label="主体CN" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTag(row.certType)">{{ typeText(row.certType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="算法" width="90">
          <template #default="{ row }">{{ row.algorithm }}</template>
        </el-table-column>
        <el-table-column prop="issuerCn" label="颁发CA" min-width="170" show-overflow-tooltip />
        <el-table-column prop="serialHex" label="序列号" min-width="150" show-overflow-tooltip class-name="cell-mono" />
        <el-table-column label="有效期" width="300">
          <template #default="{ row }">{{ row.validFrom }} ~ {{ row.validTo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="用途说明" min-width="140" show-overflow-tooltip />
        <el-table-column align="right" label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button
              v-for="a in visibleRowActions(row)"
              :key="a.label"
              link
              size="small"
              :type="a.type || 'primary'"
              :disabled="a.disabled"
              @click="a.run()"
            >{{ a.label }}</el-button>
            <el-dropdown v-if="moreRowActions(row).length" trigger="click" @command="cmd => cmd.run()">
              <el-button link type="primary" size="small">
                更多<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="a in moreRowActions(row)"
                    :key="a.label"
                    :command="a"
                    :disabled="a.disabled"
                  >{{ a.label }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的证书记录</div>
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

    <!-- 签发证书 -->
    <el-dialog v-model="issueVisible" title="签发证书" width="640px" :close-on-click-modal="false">
      <el-alert
        v-if="isTenantDomain"
        type="info"
        :closable="false"
        show-icon
        :title="`租户域「${tenantName || tenantDomain}」签发：证书主体归属由租户确定，无需手填，仅需填写主体CN。`"
        style="margin-bottom: 12px"
      />
      <el-form :model="issueForm" label-width="150px">
        <el-form-item label="主体CN" required>
          <el-input v-model="issueForm.subjectCn" :placeholder="isTenantDomain ? '仅填主体CN，如 his.yibao.local（主体归属由后端按租户附加）' : '如 his.cslk.local / csmig 前置机'" />
        </el-form-item>
        <el-form-item label="证书类型">
          <el-radio-group v-model="issueForm.certType">
            <el-radio-button value="SERVER">服务端</el-radio-button>
            <el-radio-button value="CLIENT">客户端</el-radio-button>
            <el-radio-button value="SUB_CA">二级CA</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="算法">
          <el-radio-group v-model="issueForm.algorithm">
            <el-radio-button value="RSA2048">RSA 2048</el-radio-button>
            <el-radio-button value="SM2">SM2 国密</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="issueForm.validityDays" :min="1" :max="36500" />
        </el-form-item>
        <el-form-item label="主体别名（SAN）">
          <el-input v-model="issueForm.san" placeholder="逗号分隔：DNS或IP，如 his.cslk.local,192.168.1.10" />
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="issueForm.description" type="textarea" :rows="2" placeholder="颁发给哪个子系统/服务" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="issueVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onIssue">签发</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- CSR 签发 -->
    <el-dialog v-model="csrVisible" title="证书请求签发（CSR）" width="680px" :close-on-click-modal="false">
      <el-form :model="csrForm" label-width="150px">
        <el-form-item label="证书请求内容（CSR）" required>
          <el-input
            v-model="csrForm.csrPem"
            type="textarea"
            :rows="8"
            placeholder="-----BEGIN CERTIFICATE REQUEST----- ..."
          />
        </el-form-item>
        <el-form-item label="证书类型">
          <el-radio-group v-model="csrForm.certType">
            <el-radio-button value="SERVER">服务端</el-radio-button>
            <el-radio-button value="CLIENT">客户端</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="csrForm.validityDays" :min="1" :max="36500" />
        </el-form-item>
        <el-form-item label="主体别名（SAN）">
          <el-input v-model="csrForm.san" placeholder="CSR自带SAN时优先使用CSR的" />
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="csrForm.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="csrVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onCsrIssue">CSR签发</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 公钥直签（RA 场景：申请方自持私钥） -->
    <el-dialog v-model="pubKeyVisible" title="公钥直签（申请方自持私钥，仅提交公钥）" width="680px" :close-on-click-modal="false">
      <el-alert
        v-if="isTenantDomain"
        type="info"
        :closable="false"
        show-icon
        :title="`租户域「${tenantName || tenantDomain}」签发：证书主体归属由租户确定，仅需填写主体CN。`"
        style="margin-bottom: 12px"
      />
      <el-form :model="pubKeyForm" label-width="150px">
        <el-form-item label="公钥PEM" required>
          <el-input
            v-model="pubKeyForm.publicKeyPem"
            type="textarea"
            :rows="7"
            placeholder="-----BEGIN PUBLIC KEY----- ...（SPKI 格式，RSA2048 或 SM2 均可）"
          />
        </el-form-item>
        <el-form-item label="证书模板">
          <el-select v-model="pubKeyForm.templateId" clearable placeholder="不选则按下发参数签发" style="width: 100%">
            <el-option v-for="t in templates" :key="t.id" :label="`${t.name}（${t.algorithm || '-'}）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="主体CN" required>
          <el-input v-model="pubKeyForm.subjectCn" placeholder="如 his.cslk.local / 张三" />
        </el-form-item>
        <el-form-item label="证书类型">
          <el-radio-group v-model="pubKeyForm.certType">
            <el-radio-button value="SERVER">服务端</el-radio-button>
            <el-radio-button value="CLIENT">客户端</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期(天)">
          <el-input-number v-model="pubKeyForm.validityDays" :min="1" :max="36500" />
        </el-form-item>
        <el-form-item label="主体别名（SAN）">
          <el-input v-model="pubKeyForm.san" placeholder="逗号分隔：DNS或IP（模板锁定SAN时以模板为准）" />
        </el-form-item>
        <el-form-item label="用途说明">
          <el-input v-model="pubKeyForm.description" type="textarea" :rows="2" placeholder="颁发给哪个子系统/服务" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="pubKeyVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onPubKeyIssue">签发</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 挂起（发起操作单，待他人审批后执行） -->
    <el-dialog v-model="suspendVisible" title="发起挂起操作单（GM/T 0034 §12.6，可解除）" width="460px" :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        为证书 <b>{{ suspendTarget?.subjectCn }}</b> 发起挂起申请。提交后生成待审批操作单，
        由持审批权限的其他人员批准后才实际执行（挂起期间进 CRL certificateHold、OCSP 返回 on-hold）。
      </p>
      <el-form label-width="90px">
        <el-form-item label="原因">
          <el-select v-model="suspendReason" style="width: 100%">
            <el-option label="证书挂起（默认）" value="CERTIFICATE_HOLD" />
            <el-option label="人员离职/待定" value="AFFILIATION_CHANGED" />
            <el-option label="设备临时停用" value="CESSATION_OF_OPERATION" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="suspendVisible = false">取消</el-button>
          <el-button type="warning" :loading="submitting" @click="onSuspendConfirm">提交审批</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 更新 / 重签（发起操作单，待他人审批后执行） -->
    <el-dialog v-model="renewVisible" :title="renewAction === 'renew' ? '发起证书更新操作单（GM/T 0034 §12.2）' : '发起密钥更新操作单（rekey，GM/T 0034 §12.2）'" width="500px" :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        {{ renewAction === 'renew'
          ? `为证书 ${renewTarget?.subjectCn} 发起更新申请：批准后原证书吊销（SUPERSEDED），以其公钥重签新证书（私钥仍由持有方自持）。`
          : `为证书 ${renewTarget?.subjectCn} 发起重签申请：批准后原证书吊销（SUPERSEDED），由 CA 生成新密钥对重签（仅限私钥在 CA 侧的在线签发证书）。` }}
        提交后生成待审批操作单，由持审批权限的其他人员批准后才实际执行。
      </p>
      <el-form label-width="110px">
        <el-form-item label="新有效期(天)">
          <el-input-number v-model="renewDays" :min="1" :max="36500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="renewVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onRenewConfirm">提交审批</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 导出 PKCS#12 -->
    <el-dialog v-model="p12Visible" title="导出 PKCS#12（软件证书，PBE 口令加密）" width="460px" :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        导出 <b>{{ p12Target?.subjectCn }}</b> 的证书链（叶子+二级CA+根）与私钥为 .p12 文件，
        安装到浏览器/业务系统/中间件时输入此口令。私钥由 CA 侧持有方可导出。
      </p>
      <el-form label-width="90px">
        <el-form-item label="口令" required>
          <el-input v-model="p12Password" type="password" show-password placeholder="至少4位，请妥善保管" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="p12Visible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onExportP12">导出</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 吊销（发起操作单，待他人审批后执行） -->
    <el-dialog v-model="revokeVisible" title="发起吊销操作单" width="460px" :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        为证书 <b>{{ revokeTarget?.subjectCn }}</b> 发起吊销申请。提交后生成待审批操作单，
        由持审批权限的其他人员批准后才实际执行（吊销后立即失效并进入 CRL）。
      </p>
      <el-form label-width="90px">
        <el-form-item label="吊销原因">
          <el-select v-model="revokeReason" style="width: 100%">
            <el-option label="密钥泄露" value="KEY_COMPROMISE" />
            <el-option label="CA泄露" value="CA_COMPROMISE" />
            <el-option label="隶属关系变更" value="AFFILIATION_CHANGED" />
            <el-option label="被新证书替代" value="SUPERSEDED" />
            <el-option label="停止运营" value="CESSATION_OF_OPERATION" />
            <el-option label="证书挂起" value="CERTIFICATE_HOLD" />
            <el-option label="权限撤销" value="PRIVILEGE_WITHDRAWN" />
            <el-option label="未指定" value="UNSPECIFIED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="revokeVisible = false">取消</el-button>
          <el-button type="danger" :loading="submitting" @click="onRevokeConfirm">提交审批</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 证书详情（行点击） -->
    <el-dialog v-model="detailVisible" title="证书详情" width="600px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="序列号"><span class="mono-wrap">{{ detail?.serialHex }}</span></el-descriptions-item>
        <el-descriptions-item label="主体CN">{{ detail?.subjectCn }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detail ? typeText(detail.certType) : '' }}</el-descriptions-item>
        <el-descriptions-item label="算法">{{ detail?.algorithm }}</el-descriptions-item>
        <el-descriptions-item label="颁发CA">{{ detail?.issuerCn }}</el-descriptions-item>
        <el-descriptions-item label="主体别名（SAN）">{{ detail?.san || '无' }}</el-descriptions-item>
        <el-descriptions-item label="有效期">{{ detail?.validFrom }} ~ {{ detail?.validTo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail?.revokedAt" label="吊销时间/原因">{{ detail?.revokedAt }}（{{ detail?.revokeReason }}）</el-descriptions-item>
        <el-descriptions-item label="所属租户">{{ detail?.tenantName || detail?.tenantKey || '全局' }}</el-descriptions-item>
        <el-descriptions-item label="签发人">{{ detail?.createdByName || detail?.createdBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '无' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, ArrowDown } from '@element-plus/icons-vue'
import {
  pageCerts,
  issueCert,
  issueCertByCsr,
  issueCertByPublicKey,
  pageTemplates,
  submitCertOp,
  exportCertP12,
  downloadCertPem,
  downloadKeyPem,
  listTenantsCached
} from '@/api/system'
import { downloadText } from '@/lib/download'
import { currentTenant } from '@/lib/tenant'
import { hasPerm, permReady } from '@/lib/perm'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'

// 按钮权限点与后端接口校验一一对应；旧会话（无权限缓存）回退角色判断，服务端仍强校验
const role = localStorage.getItem('role') || ''
const permLoaded = permReady()
const canIssue = permLoaded ? hasPerm('cert:issue') : role === 'OPERATOR'
const canDownloadCert = permLoaded ? hasPerm('cert:download') : ['ADMIN', 'OPERATOR'].includes(role)
const canDownloadKey = permLoaded ? hasPerm('cert:key:download') : role === 'KEY_ADMIN'

// 当前租户域：列表/签发/吊销等请求由 request 封装自动附带 tenantKey=currentTenant()
const tenantDomain = ref(currentTenant())
const tenantName = ref('')
const isTenantDomain = computed(() => !!tenantDomain.value)

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, certType: '', status: '', keyword: '' })

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()

const issueVisible = ref(false)
const csrVisible = ref(false)
const revokeVisible = ref(false)
const revokeTarget = ref(null)
const revokeReason = ref('KEY_COMPROMISE')
const suspendVisible = ref(false)
const suspendTarget = ref(null)
const suspendReason = ref('CERTIFICATE_HOLD')
const renewVisible = ref(false)
const renewAction = ref('renew')
const renewTarget = ref(null)
const renewDays = ref(365)
const p12Visible = ref(false)
const p12Target = ref(null)
const p12Password = ref('')
const submitting = ref(false)
const detailVisible = ref(false)
const detail = ref(null)

const defaultDays = 365

const pubKeyVisible = ref(false)
const templates = ref([])
const pubKeyForm = reactive({
  publicKeyPem: '',
  templateId: null,
  subjectCn: '',
  certType: 'SERVER',
  validityDays: defaultDays,
  san: '',
  description: ''
})

const issueForm = reactive({
  subjectCn: '',
  certType: 'SERVER',
  algorithm: 'RSA2048',
  validityDays: defaultDays,
  san: '',
  description: ''
})

const csrForm = reactive({
  csrPem: '',
  certType: 'SERVER',
  validityDays: defaultDays,
  san: '',
  description: ''
})

function typeText(t) {
  return { SERVER: '服务端', CLIENT: '客户端', SUB_CA: '二级CA', ROOT: '根CA' }[t] || t
}
function typeTag(t) {
  return { SERVER: '', CLIENT: 'success', SUB_CA: 'warning', ROOT: 'danger' }[t] || ''
}
function statusText(s) {
  return { VALID: '有效', REVOKED: '已吊销', FROZEN: '已挂起', EXPIRED: '已过期' }[s] || s
}
function statusTag(s) {
  return { VALID: 'success', REVOKED: 'danger', FROZEN: 'warning', EXPIRED: 'info' }[s] || ''
}

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
}

// 操作列口径：前 3 个平铺，其余收进「更多」下拉（列表页统一模式）
const ACTION_VISIBLE = 3

function certActions(row) {
  const acts = [{ label: '详情', run: () => onRowDetail(row) }]
  if (canDownloadCert) acts.push({ label: '下载证书', run: () => onDownloadCert(row) })
  // 私钥类操作只对 CA 侧落了私钥的证书开放（CSR/公钥直签的私钥由申请方自持，CA 无钥可下载）
  if (canDownloadKey && row.keyHeld) acts.push({ label: '下载私钥', run: () => onDownloadKey(row) })
  if (canDownloadKey && row.keyHeld && ['SERVER', 'CLIENT'].includes(row.certType)) {
    acts.push({ label: '导出P12', run: () => openExportP12(row) })
  }
  if (hasPerm('cert:suspend')) {
    if (row.status === 'VALID') {
      acts.push({ label: '挂起', type: 'warning', disabled: row.certType === 'ROOT', run: () => openSuspend(row) })
    }
    if (row.status === 'FROZEN') {
      acts.push({ label: '解除挂起', type: 'success', run: () => onUnsuspend(row) })
    }
  }
  if (hasPerm('cert:renew') && ['VALID', 'FROZEN'].includes(row.status)) {
    acts.push({ label: '更新', disabled: row.certType === 'ROOT', run: () => openRenew(row, 'renew') })
  }
  if (hasPerm('cert:rekey') && ['VALID', 'FROZEN'].includes(row.status) && row.keyHeld
      && !['ROOT', 'SUB_CA'].includes(row.certType)) {
    acts.push({ label: '重签', run: () => openRenew(row, 'rekey') })
  }
  if (hasPerm('cert:revoke')) {
    acts.push({ label: '吊销', type: 'danger', disabled: row.status !== 'VALID' || row.certType === 'ROOT', run: () => onRevoke(row) })
  }
  return acts
}

function visibleRowActions(row) {
  return certActions(row).slice(0, ACTION_VISIBLE)
}

function moreRowActions(row) {
  return certActions(row).slice(ACTION_VISIBLE)
}

async function load() {
  loading.value = true
  try {
    const res = await pageCerts({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      certType: query.certType || undefined,
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
  query.certType = ''
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

function openIssue() {
  Object.assign(issueForm, {
    subjectCn: '',
    certType: 'SERVER',
    algorithm: 'RSA2048',
    validityDays: defaultDays,
    san: '',
    description: ''
  })
  issueVisible.value = true
}

async function onIssue() {
  if (!issueForm.subjectCn.trim()) {
    ElMessage.warning('请填写主体CN')
    return
  }
  submitting.value = true
  try {
    await issueCert({ ...issueForm, validityDays: issueForm.validityDays || defaultDays })
    ElMessage.success('签发成功，私钥已落盘（管理端可下载）')
    issueVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

function openCsr() {
  Object.assign(csrForm, {
    csrPem: '',
    certType: 'SERVER',
    validityDays: defaultDays,
    san: '',
    description: ''
  })
  csrVisible.value = true
}

async function onCsrIssue() {
  if (!csrForm.csrPem.trim()) {
    ElMessage.warning('请粘贴 CSR 内容')
    return
  }
  submitting.value = true
  try {
    await issueCertByCsr({ ...csrForm, validityDays: csrForm.validityDays || defaultDays })
    ElMessage.success('CSR签发成功（私钥由申请方自持）')
    csrVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function openPubKey() {
  Object.assign(pubKeyForm, {
    publicKeyPem: '',
    templateId: null,
    subjectCn: '',
    certType: 'SERVER',
    validityDays: defaultDays,
    san: '',
    description: ''
  })
  pubKeyVisible.value = true
  if (templates.value.length === 0) {
    try {
      const res = await pageTemplates({ pageNo: 1, pageSize: 100 })
      templates.value = res.data.records || []
    } catch (e) {
      // 模板加载失败不阻断手填签发参数
    }
  }
}

async function onPubKeyIssue() {
  if (!pubKeyForm.publicKeyPem.trim()) {
    ElMessage.warning('请粘贴公钥 PEM')
    return
  }
  if (!pubKeyForm.subjectCn.trim()) {
    ElMessage.warning('请填写主体CN')
    return
  }
  submitting.value = true
  try {
    await issueCertByPublicKey({
      subjectDn: `CN=${pubKeyForm.subjectCn.trim()}`,
      publicKeyPem: pubKeyForm.publicKeyPem,
      certType: pubKeyForm.certType,
      validityDays: pubKeyForm.validityDays || defaultDays,
      templateId: pubKeyForm.templateId || undefined,
      san: pubKeyForm.san || undefined,
      description: pubKeyForm.description || undefined
    })
    ElMessage.success('公钥直签成功（私钥由申请方自持，CA 不留存）')
    pubKeyVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

function onRevoke(row) {
  revokeTarget.value = row
  revokeReason.value = 'KEY_COMPROMISE'
  revokeVisible.value = true
}

function openSuspend(row) {
  suspendTarget.value = row
  suspendReason.value = 'CERTIFICATE_HOLD'
  suspendVisible.value = true
}

async function onSuspendConfirm() {
  submitting.value = true
  try {
    const res = await submitCertOp({ opType: 'SUSPEND', certId: suspendTarget.value.id, reason: suspendReason.value })
    ElMessage.success(`挂起操作单已提交（单号 ${res.data.opNo}），等待他人审批后执行`)
    suspendVisible.value = false
  } finally {
    submitting.value = false
  }
}

async function onUnsuspend(row) {
  await ElMessageBox.confirm(`为证书 ${row.subjectCn} 发起解除挂起申请？提交后由持审批权限的其他人员批准后执行。`, '发起操作单', { type: 'warning' })
  submitting.value = true
  try {
    const res = await submitCertOp({ opType: 'UNSUSPEND', certId: row.id })
    ElMessage.success(`解除挂起操作单已提交（单号 ${res.data.opNo}），等待他人审批后执行`)
  } finally {
    submitting.value = false
  }
}

function openRenew(row, action) {
  renewTarget.value = row
  renewAction.value = action
  renewDays.value = Math.max(1, Math.round((new Date(row.validTo) - new Date(row.validFrom)) / 86400000)) || 365
  renewVisible.value = true
}

async function onRenewConfirm() {
  submitting.value = true
  try {
    const opType = renewAction.value === 'renew' ? 'RENEW' : 'REKEY'
    const res = await submitCertOp({ opType, certId: renewTarget.value.id, validityDays: renewDays.value })
    ElMessage.success(`${opType === 'RENEW' ? '证书更新' : '密钥更新'}操作单已提交（单号 ${res.data.opNo}），等待他人审批后执行`)
    renewVisible.value = false
  } finally {
    submitting.value = false
  }
}

function openExportP12(row) {
  p12Target.value = row
  p12Password.value = ''
  p12Visible.value = true
}

async function onExportP12() {
  if (!p12Password.value || p12Password.value.length < 4) {
    ElMessage.warning('口令不能少于4位')
    return
  }
  submitting.value = true
  try {
    const blob = await exportCertP12(p12Target.value.id, p12Password.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `cert-${p12Target.value.id}.p12`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('PKCS#12 已导出，口令请妥善保管')
    p12Visible.value = false
  } finally {
    submitting.value = false
  }
}

async function onRevokeConfirm() {
  submitting.value = true
  try {
    const res = await submitCertOp({ opType: 'REVOKE', certId: revokeTarget.value.id, reason: revokeReason.value })
    ElMessage.success(`吊销操作单已提交（单号 ${res.data.opNo}），等待他人审批后执行`)
    revokeVisible.value = false
  } finally {
    submitting.value = false
  }
}

async function onDownloadCert(row) {
  const pem = await downloadCertPem(row.id)
  downloadText(pem, `${row.subjectCn}-cert.pem`)
  ElMessage.success('证书已下载')
}

async function onDownloadKey(row) {
  try {
    const pem = await downloadKeyPem(row.id)
    downloadText(pem, `${row.subjectCn}-key.pem`)
    ElMessage.success('私钥已下载，请妥善保管')
  } catch (e) {
    // 开关关闭/无私钥等错误由拦截器提示
  }
}

onMounted(async () => {
  load()
  if (tenantDomain.value) {
    const hit = (await listTenantsCached()).find(o => o.tenantKey === tenantDomain.value)
    tenantName.value = hit?.tenantName || ''
  }
})
</script>

<style scoped>
.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
