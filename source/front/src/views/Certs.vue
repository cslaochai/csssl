<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="公司">
            <el-select v-model="query.companyUscc" clearable placeholder="全部" style="width: 300px"
                       @change="handleSearch">
              <el-option
                  v-for="o in companyOptions"
                  :key="o.uscc"
                  :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                  :value="o.uscc"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="证书类型">
            <el-select v-model="query.certType" clearable placeholder="全部" style="width: 130px"
                       @change="handleSearch">
              <el-option label="服务端" value="SERVER"/>
              <el-option label="客户端" value="CLIENT"/>
              <el-option label="根CA" value="ROOT"/>
              <el-option label="二级CA" value="SUB_CA"/>
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px" @change="handleSearch">
              <el-option label="有效" value="VALID"/>
              <el-option label="已吊销" value="REVOKED"/>
              <el-option label="已挂起" value="FROZEN"/>
              <el-option label="已过期" value="EXPIRED"/>
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input
                v-model="query.keyword"
                clearable
                placeholder="主体CN / 序列号"
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
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column label="主体CN" min-width="180" prop="subjectCn" show-overflow-tooltip/>
        <el-table-column label="所属公司" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.companyName || row.companyUscc || '-' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.certType)" size="small">{{ typeText(row.certType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="算法" width="90">
          <template #default="{ row }">{{ row.algorithm }}</template>
        </el-table-column>
        <el-table-column label="颁发CA" min-width="170" prop="issuerCn" show-overflow-tooltip/>
        <el-table-column class-name="cell-mono" label="序列号" min-width="150" prop="serialHex" show-overflow-tooltip/>
        <el-table-column label="有效期" width="300">
          <template #default="{ row }">{{ row.validFrom }} ~ {{ row.validTo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用途说明" min-width="140" prop="description" show-overflow-tooltip/>
        <el-table-column align="right" fixed="right" label="操作" :width="actionColWidth">
          <template #default="{ row }">
            <el-button
                v-for="a in certActions(row)"
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

    <!-- 挂起（发起操作单，待他人审批后执行） -->
    <el-dialog v-model="suspendVisible" :close-on-click-modal="false" title="发起挂起操作单（GM/T 0034 §12.6，可解除）"
               width="460px">
      <p style="margin-bottom: 12px">
        为证书 <b>{{ suspendTarget?.subjectCn }}</b> 发起挂起申请。提交后生成待审批操作单，
        由持审批权限的其他人员批准后才实际执行（挂起期间进 CRL certificateHold、OCSP 返回 on-hold）。
      </p>
      <el-form label-width="90px">
        <el-form-item label="原因">
          <el-select v-model="suspendReason" style="width: 100%">
            <el-option label="证书挂起（默认）" value="CERTIFICATE_HOLD"/>
            <el-option label="人员离职/待定" value="AFFILIATION_CHANGED"/>
            <el-option label="设备临时停用" value="CESSATION_OF_OPERATION"/>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="suspendVisible = false">取消</el-button>
          <el-button :loading="submitting" type="warning" @click="onSuspendConfirm">提交审批</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 更新 / 重签（发起操作单，待他人审批后执行） -->
    <el-dialog v-model="renewVisible"
               :close-on-click-modal="false"
               :title="renewAction === 'renew' ? '发起证书更新操作单（GM/T 0034 §12.2）' : '发起密钥更新操作单（rekey，GM/T 0034 §12.2）'" width="500px">
      <p style="margin-bottom: 12px">
        {{
          renewAction === 'renew'
              ? `为证书 ${renewTarget?.subjectCn} 发起更新申请：批准后原证书吊销（SUPERSEDED），以其公钥重签新证书（私钥仍由持有方自持）。`
              : `为证书 ${renewTarget?.subjectCn} 发起重签申请：批准后原证书吊销（SUPERSEDED），由 CA 生成新密钥对重签（仅限 CA 侧代持私钥的历史证书）。`
        }}
        提交后生成待审批操作单，由持审批权限的其他人员批准后才实际执行。
      </p>
      <el-form label-width="110px">
        <el-form-item label="新有效期(天)">
          <el-input-number v-model="renewDays" :max="36500" :min="1"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="renewVisible = false">取消</el-button>
          <el-button :loading="submitting" type="primary" @click="onRenewConfirm">提交审批</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 导出 PKCS#12 -->
    <el-dialog v-model="p12Visible" :close-on-click-modal="false" title="导出 PKCS#12（软件证书，PBE 口令加密）"
               width="460px">
      <p style="margin-bottom: 12px">
        导出 <b>{{ p12Target?.subjectCn }}</b> 的证书链（叶子+二级CA+根）与私钥为 .p12 文件，
        安装到浏览器/业务系统/中间件时输入此口令。私钥由 CA 侧持有方可导出。
      </p>
      <el-form label-width="90px">
        <el-form-item label="口令" required>
          <el-input v-model="p12Password" placeholder="至少4位，请妥善保管" show-password type="password"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="p12Visible = false">取消</el-button>
          <el-button :loading="submitting" type="primary" @click="onExportP12">导出</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 吊销（发起操作单，待他人审批后执行） -->
    <el-dialog v-model="revokeVisible" :close-on-click-modal="false" title="发起吊销操作单" width="460px">
      <p style="margin-bottom: 12px">
        为证书 <b>{{ revokeTarget?.subjectCn }}</b> 发起吊销申请。提交后生成待审批操作单，
        由持审批权限的其他人员批准后才实际执行（吊销后立即失效并进入 CRL）。
      </p>
      <el-form label-width="90px">
        <el-form-item label="吊销原因">
          <el-select v-model="revokeReason" style="width: 100%">
            <el-option label="密钥泄露" value="KEY_COMPROMISE"/>
            <el-option label="CA泄露" value="CA_COMPROMISE"/>
            <el-option label="隶属关系变更" value="AFFILIATION_CHANGED"/>
            <el-option label="被新证书替代" value="SUPERSEDED"/>
            <el-option label="停止运营" value="CESSATION_OF_OPERATION"/>
            <el-option label="证书挂起" value="CERTIFICATE_HOLD"/>
            <el-option label="权限撤销" value="PRIVILEGE_WITHDRAWN"/>
            <el-option label="未指定" value="UNSPECIFIED"/>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="revokeVisible = false">取消</el-button>
          <el-button :loading="submitting" type="danger" @click="onRevokeConfirm">提交审批</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 该公司的签发链：根CA 置顶，向下辐射各二级CA（含已过期/被接管的，便于看历史） -->
    <el-dialog v-model="subCaVisible" :close-on-click-modal="false"
               :title="`证书签发链（${subCaRoot?.companyName || subCaRoot?.companyUscc || '全局'}）`" width="960px">
      <div v-loading="subCaLoading" class="cert-tree">
        <div class="tree-scroll">
          <div v-if="subCaRoot" class="tree-inner">
            <div class="cert-node is-root" :class="{'is-dead': subCaRoot.status !== 'VALID'}">
              <div class="node-head">
                <span class="node-kind">根CA</span>
                <el-tag :type="statusTag(subCaRoot.status)" disable-transitions size="small">{{ statusText(subCaRoot.status) }}</el-tag>
              </div>
              <div class="node-cn" :title="subCaRoot.subjectCn">{{ subCaRoot.subjectCn }}</div>
              <div class="node-meta">
                <div class="meta-row"><span class="meta-label">算法</span><span>{{ subCaRoot.algorithm }}</span></div>
                <div class="meta-row"><span class="meta-label">序列号</span>
                  <span class="meta-mono" :title="subCaRoot.serialHex">{{ subCaRoot.serialHex }}</span></div>
                <div class="meta-row"><span class="meta-label">生效</span><span>{{ subCaRoot.validFrom }}</span></div>
                <div class="meta-row"><span class="meta-label">到期</span><span>{{ subCaRoot.validTo }}</span></div>
              </div>
              <div class="node-acts">
                <el-button v-for="a in nodeActions(subCaRoot)" :key="a.label" :type="a.type || 'primary'" link
                           size="small" @click="a.run()">{{ a.label }}
                </el-button>
              </div>
            </div>
            <div class="tree-link-v"/>
            <div v-if="subCaRows.length" class="tree-branches">
              <div v-for="row in subCaRows" :key="row.id" class="tree-branch">
                <div class="cert-node" :class="{'is-dead': row.status !== 'VALID'}">
                  <div class="node-head">
                    <span class="node-kind">二级CA</span>
                    <el-tag :type="statusTag(row.status)" disable-transitions size="small">{{ statusText(row.status) }}</el-tag>
                  </div>
                  <div class="node-cn" :title="row.subjectCn">{{ row.subjectCn }}</div>
                  <div class="node-meta">
                    <div class="meta-row"><span class="meta-label">算法</span><span>{{ row.algorithm }}</span></div>
                    <div class="meta-row"><span class="meta-label">序列号</span>
                      <span class="meta-mono" :title="row.serialHex">{{ row.serialHex }}</span></div>
                    <div class="meta-row"><span class="meta-label">生效</span><span>{{ row.validFrom }}</span></div>
                    <div class="meta-row"><span class="meta-label">到期</span><span>{{ row.validTo }}</span></div>
                  </div>
                  <div class="node-acts">
                    <el-button v-for="a in nodeActions(row)" :key="a.label" :type="a.type || 'primary'" link
                               size="small" @click="a.run()">{{ a.label }}
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
            <div v-else-if="!subCaLoading" class="empty-tip">暂无匹配的二级CA</div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 证书详情（行点击） -->
    <el-dialog v-model="detailVisible" title="证书详情" width="600px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="序列号"><span class="mono-wrap">{{ detail?.serialHex }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="主体CN">{{ detail?.subjectCn }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detail ? typeText(detail.certType) : '' }}</el-descriptions-item>
        <el-descriptions-item label="算法">{{ detail?.algorithm }}</el-descriptions-item>
        <el-descriptions-item label="颁发CA">{{ detail?.issuerCn }}</el-descriptions-item>
        <el-descriptions-item label="主体别名（SAN）">{{ detail?.san || '无' }}</el-descriptions-item>
        <el-descriptions-item label="有效期">{{ detail?.validFrom }} ~ {{ detail?.validTo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail?.revokedAt" label="吊销时间/原因">{{
            detail?.revokedAt
          }}（{{ detail?.revokeReason }}）
        </el-descriptions-item>
        <el-descriptions-item label="所属公司">{{
            detail?.companyName || detail?.companyUscc || '全局'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="签发人">{{
            detail?.createdByName || '无'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="用途说明">{{ detail?.description || '无' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {Refresh, Search} from '@element-plus/icons-vue'
import {
  downloadCertPem,
  downloadCertZip,
  downloadKeyPem,
  exportCertP12,
  listCompaniesCached,
  pageCerts,
  submitCertOp
} from '@/api/system'
import {downloadBlob, downloadText} from '@/lib/download'
import {hasPerm, permReady} from '@/lib/perm'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'

// 按钮权限点与后端接口校验一一对应；旧会话（无权限缓存）回退角色判断，服务端仍强校验
const role = localStorage.getItem('role') || ''
const permLoaded = permReady()
const canDownloadCert = permLoaded ? hasPerm('cert:download') : ['OPERATOR', 'CERT_OFFICER'].includes(role)
const canDownloadKey = permLoaded ? hasPerm('cert:key:download') : role === 'KEY_ADMIN'

// 公司域由页面显式选择：列表可留空=跨公司只读，操作单必须指定目标公司
// 本页是台账不是发证入口：直发已删除，发证只剩 RA 证书申请与二级CA重建申请
const companyOptions = ref([])

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, companyUscc: '', certType: '', status: 'VALID', keyword: ''})

// 两卡式列表页：表格只设最大高度，超高内部滚动
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

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
const subCaVisible = ref(false)
const subCaRoot = ref(null)
const subCaRows = ref([])
const subCaLoading = ref(false)

function typeText(t) {
  return {SERVER: '服务端', CLIENT: '客户端', SUB_CA: '二级CA', ROOT: '根CA'}[t] || t
}

function typeTag(t) {
  return {SERVER: '', CLIENT: 'success', SUB_CA: 'warning', ROOT: 'danger'}[t] || ''
}

function statusText(s) {
  return {VALID: '有效', REVOKED: '已吊销', FROZEN: '已挂起', EXPIRED: '已过期'}[s] || s
}

function statusTag(s) {
  return {VALID: 'success', REVOKED: 'danger', FROZEN: 'warning', EXPIRED: 'info'}[s] || ''
}

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
}

// 该公司的二级CA：主列表不铺开，点根CA行的「二级CA」按公司取（含已过期/被接管的，便于看历史）
async function openSubCaList(root) {
  subCaRoot.value = root
  subCaVisible.value = true
  subCaLoading.value = true
  try {
    const res = await pageCerts({
      pageNo: 1,
      pageSize: 50,
      certType: 'SUB_CA',
      companyUscc: root.companyUscc || undefined
    })
    const list = res.data.records || []
    // 存量 default 域根证书未挂公司编码时，按签发者兜底，避免跨公司串数据
    subCaRows.value = root.companyUscc ? list : list.filter(x => x.issuerCn === root.subjectCn)
  } catch (e) {
    subCaRows.value = []
  } finally {
    subCaLoading.value = false
  }
}

// 操作列口径：只渲染当前这一行真能做的动作（不合规的直接不出现，不置灰）；全部平铺，不再折叠成「更多」
const LEAF_TYPES = ['SERVER', 'CLIENT']

function certActions(row) {
  const isLeaf = LEAF_TYPES.includes(row.certType)
  const usable = row.status === 'VALID'
  const acts = [{label: '详情', run: () => onRowDetail(row)}]
  // 证书本体任何状态都可下载（历史验签、审计留档都要用）
  if (canDownloadCert) acts.push({label: '下载证书', run: () => onDownloadCert(row)})
  // 导出 ZIP / P12 是把证书交付业务系统「接着用」，只对仍在有效期内的终端证书有意义
  if (canDownloadCert && isLeaf && usable) acts.push({label: '导出ZIP', run: () => onDownloadZip(row)})
  // 私钥类操作只对 CA 侧落了私钥的证书开放（走 RA 申请签发的证书私钥由申请人自持，CA 无钥可下载）；
  // 已吊销 = 密钥已宣告作废，不再外发
  if (canDownloadKey && row.keyHeld && row.status !== 'REVOKED') acts.push({label: '下载私钥', run: () => onDownloadKey(row)})
  if (canDownloadKey && row.keyHeld && isLeaf && usable) {
    acts.push({label: '导出P12', run: () => openExportP12(row)})
  }
  // 生命周期操作只针对终端证书：根/二级CA 走「根CA管理」的封存与重建，不在此处挂/吊销/续；
  // 二级CA 不混排在主列表，由根CA 行的「二级CA」入口按公司查看
  if (row.certType === 'ROOT') acts.push({label: '二级CA', run: () => openSubCaList(row)})
  if (!isLeaf) return acts
  if (hasPerm('cert:suspend') && usable) {
    acts.push({label: '挂起', type: 'warning', run: () => openSuspend(row)})
  }
  if (hasPerm('cert:suspend') && row.status === 'FROZEN') {
    acts.push({label: '解除挂起', type: 'success', run: () => onUnsuspend(row)})
  }
  if (hasPerm('cert:renew') && ['VALID', 'FROZEN'].includes(row.status)) {
    acts.push({label: '更新', run: () => openRenew(row, 'renew')})
  }
  if (hasPerm('cert:rekey') && ['VALID', 'FROZEN'].includes(row.status) && row.keyHeld) {
    acts.push({label: '重签', run: () => openRenew(row, 'rekey')})
  }
  if (hasPerm('cert:revoke') && usable) {
    acts.push({label: '吊销', type: 'danger', run: () => onRevoke(row)})
  }
  return acts
}

// 签发链树上的动作：与列表同一口径，但「二级CA」是当前视图自身，树上不再重复
function nodeActions(row) {
  return certActions(row).filter(a => a.label !== '二级CA')
}

// 平铺后列宽随行数变化：按主列表最宽的一行估算（汉字 14px / 半角 8px，含按钮间距与单元格内边距）
const actionColWidth = computed(() => {
  const widthOf = (row) => certActions(row).reduce((sum, a) => {
    const px = [...a.label].reduce((w, ch) => w + (ch.charCodeAt(0) > 255 ? 14 : 8), 0)
    return sum + px + 12
  }, 0)
  const widest = rows.value.reduce((max, row) => Math.max(max, widthOf(row)), 0)
  return widest ? widest + 24 : 120
})

async function load() {
  loading.value = true
  try {
    const res = await pageCerts({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      companyUscc: query.companyUscc || undefined,
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
  query.companyUscc = ''
  query.certType = ''
  query.status = 'VALID'
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
    const res = await submitCertOp({opType: 'SUSPEND', certId: suspendTarget.value.id, reason: suspendReason.value},
        suspendTarget.value.companyUscc)
    ElMessage.success(`挂起操作单已提交（单号 ${res.data.opNo}），等待他人审批后执行`)
    suspendVisible.value = false
  } finally {
    submitting.value = false
  }
}

async function onUnsuspend(row) {
  await ElMessageBox.confirm(`为证书 ${row.subjectCn} 发起解除挂起申请？提交后由持审批权限的其他人员批准后执行。`, '发起操作单', {type: 'warning'})
  submitting.value = true
  try {
    const res = await submitCertOp({opType: 'UNSUSPEND', certId: row.id}, row.companyUscc)
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
    const res = await submitCertOp({opType, certId: renewTarget.value.id, validityDays: renewDays.value},
        renewTarget.value.companyUscc)
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
    const res = await submitCertOp({opType: 'REVOKE', certId: revokeTarget.value.id, reason: revokeReason.value},
        revokeTarget.value.companyUscc)
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

// 导出 ZIP（不加密，内含证书链 PEM）：线下场景给 HIS 等外部系统「上传 zip 回填」用
async function onDownloadZip(row) {
  try {
    const blob = await downloadCertZip(row.id)
    downloadBlob(blob, `${row.subjectCn}-cert.zip`)
    ElMessage.success('证书压缩包已导出（未加密，内含证书链 PEM）')
  } catch (e) {
    // 错误由拦截器提示
  }
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
  companyOptions.value = await listCompaniesCached()
  load()
})
</script>

<style scoped>
.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
}

/* 签发链树：根CA 居中置顶，连线用等宽分支的伪元素拼出「竖-横-竖」，二级CA 多于可视宽度时横向滚动 */
.cert-tree {
  min-height: 300px;
}

.tree-scroll {
  overflow-x: auto;
  padding-bottom: 6px;
}

.tree-inner {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  min-width: 100%;
}

.tree-link-v {
  width: 1px;
  height: 26px;
  background: #c9daea;
}

.tree-branches {
  display: flex;
  align-items: flex-start;
}

.tree-branch {
  position: relative;
  width: 268px;
  padding: 26px 8px 0;
  box-sizing: border-box;
}

.tree-branch::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  width: 1px;
  height: 26px;
  background: #c9daea;
}

.tree-branch:not(:last-child)::after {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  width: 100%;
  height: 1px;
  background: #c9daea;
}

.cert-node {
  width: 252px;
  padding: 10px 12px 8px;
  background: #fff;
  border: 1px solid var(--his-card-border);
  border-radius: 8px;
  box-shadow: var(--his-shadow-sm);
}

.cert-node.is-root {
  width: 300px;
  border-top: 2px solid var(--his-primary);
}

.cert-node.is-dead {
  background: #f7f9fb;
  box-shadow: none;
}

.cert-node.is-dead .node-cn {
  color: #8a97a5;
}

.node-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.node-kind {
  font-size: 12px;
  font-weight: 600;
  color: var(--his-title);
}

.node-cn {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #1f2d3d;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.node-meta {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--his-card-border);
  font-size: 12px;
  color: #5a6b7d;
}

.meta-row {
  display: flex;
  gap: 6px;
  line-height: 1.9;
}

.meta-label {
  flex: none;
  width: 40px;
  color: #8a97a5;
}

.meta-mono {
  font-family: Consolas, Menlo, monospace;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-acts {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--his-card-border);
  display: flex;
  flex-wrap: wrap;
  gap: 0 4px;
}
</style>
