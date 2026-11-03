<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="公司状态">
            <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px" @change="handleSearch">
              <el-option label="启用" value="ACTIVE"/>
              <el-option label="已暂停" value="SUSPENDED"/>
              <el-option label="已关闭" value="CLOSED"/>
            </el-select>
          </el-form-item>
          <el-form-item label="关键字">
            <el-input
                v-model="query.keyword"
                clearable
                placeholder="统一社会信用代码 / 名称 / 主体O"
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
          <el-button v-perm="'company:create'" :icon="Plus" type="primary" @click="openCreate">新建公司</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column class-name="cell-mono" label="统一社会信用代码" prop="uscc" show-overflow-tooltip width="200">
          <template #default="{ row }">{{ row.uscc || '-' }}</template>
        </el-table-column>
        <el-table-column label="公司名称" min-width="150" prop="companyName" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="font-medium">{{ row.companyName }}</div>
            <div v-if="row.subjectO" class="cell-sub">{{ row.subjectO }}</div>
          </template>
        </el-table-column>
        <el-table-column label="算法" prop="algorithm" width="100">
          <template #default="{ row }">{{ row.algorithm || '-' }}</template>
        </el-table-column>
        <el-table-column label="公司状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="根密钥状态" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.rootKeyState" :type="rootKeyTag(row.rootKeyState)" size="small">
              {{ rootKeyText(row.rootKeyState) }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="根CA有效期" width="350">
          <template #default="{ row }">{{ validity(row.rootValidFrom, row.rootValidTo) }}</template>
        </el-table-column>
        <el-table-column label="二级CA有效期（SUB_CA）" width="350">
          <template #default="{ row }">{{ validity(row.subCaValidFrom, row.subCaValidTo) }}</template>
        </el-table-column>
        <el-table-column label="接入ID（App ID）" show-overflow-tooltip width="250">
          <template #default="{ row }">
            <template v-if="row.apiAppId">
              <span class="cell-mono">{{ row.apiAppId }}</span>
              <div class="cell-sub">{{ row.apiClientStatus === 1 ? '接入启用' : '接入停用' }}</div>
            </template>
            <span v-else class="cell-sub">未生成</span>
          </template>
        </el-table-column>
        <el-table-column label="联系人" min-width="50">
          <template #default="{ row }">
            {{ row.contact || '-' }}
            <div v-if="row.contactPhone" class="cell-sub">{{ maskPhone(row.contactPhone) }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="300">
          <template #default="{ row }">
            <el-button
                v-for="a in companyActions(row)"
                :key="a.label"
                :disabled="a.disabled"
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

    <!-- 新建公司（直接执行） -->
    <el-dialog v-model="createVisible" :close-on-click-modal="false" title="新建公司" width="650px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="140px">
        <el-form-item label="公司名称" prop="companyName">
          <el-input v-model="form.companyName" placeholder="如 医保结算中心"/>
        </el-form-item>
        <el-form-item label="主体O" prop="subjectO">
          <el-input v-model="form.subjectO" placeholder="该公司全部证书的主体O，如 长沙麓康医院"/>
        </el-form-item>
        <el-form-item label="统一社会信用代码" prop="uscc">
          <el-input
              v-model="form.uscc"
              class="cell-mono"
              maxlength="18"
              placeholder="18 位，如 91430100MA4L5K2N3T"
              @input="onUsccInput"
          />
        </el-form-item>
        <el-form-item label="算法" prop="algorithm">
          <el-radio-group v-model="form.algorithm">
            <el-radio-button value="SM2">SM2 国密</el-radio-button>
            <el-radio-button value="RSA2048">RSA 2048</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="密钥保管人(5人)" prop="shareHolders">
          <el-select
              v-model="form.shareHolders"
              :multiple-limit="5"
              clearable
              collapse-tags
              collapse-tags-tooltip
              filterable
              multiple
              :empty-text="simpleUsers.length ? '没有匹配的密钥管理员' : '暂无密钥管理员账号，请先在「用户与角色管理」创建角色为「密钥管理员」的账号'"
              placeholder="指定 5 名根私钥份额保管人（一人一份）"
              style="width: 100%"
          >
            <el-option
                v-for="u in simpleUsers"
                :key="u.username"
                :disabled="u.status !== 1"
                :label="`${u.realName || u.username}（${u.username}${u.companyUscc ? '' : ' · 平台级'}）`"
                :value="u.username"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact" placeholder="公司CA管理员，可选"/>
        </el-form-item>
        <el-form-item label="联系方式" prop="contactPhone">
          <el-input v-model="form.contactPhone" maxlength="11" placeholder="11 位手机号，如 13800001234"/>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" :rows="2" placeholder="用途/边界说明，可选" type="textarea"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="submitting" @click="createVisible = false">取消</el-button>
          <el-button :loading="submitting" type="primary" @click="onCreate">新建公司</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 其他变更（暂停/恢复/关闭/重置接入密钥） -->
    <el-dialog v-model="opVisible" :close-on-click-modal="false" :title="opTitle" width="560px">
      <el-alert v-if="opType === 'CLOSE'" :closable="false" class="dialog-tip" show-icon
                title="关闭后该公司停签发、停 CRL 发布、接入密钥鉴权被拒；根CA密钥与历史证书保留，可再走「申请恢复」回到启用。"
                type="error"/>
      <el-alert v-else-if="opType === 'RESUME'" :closable="false" class="dialog-tip" show-icon
                title="批准执行后该公司根CA重新可签发并进入 CRL 发布，原接入密钥（App ID）同时恢复可用。"
                type="warning"/>
      <el-alert v-else-if="opType === 'RESET_CREDENTIAL'" :closable="false" class="dialog-tip" show-icon
                title="批准执行后旧接入密钥立即失效，外部系统需换成新密钥；新密钥由发起人一次性领取。" type="warning"/>
      <el-alert v-else :closable="false" class="dialog-tip" show-icon
                title="提交后需公司审批员批准才执行。" type="info"/>
      <el-descriptions :column="1" border class="op-desc">
        <el-descriptions-item label="目标公司">{{ opTarget?.companyName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="统一社会信用代码"><span class="cell-mono">{{ opTarget?.uscc || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前状态">{{ opTarget ? statusText(opTarget.status) : '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-form class="op-form" label-width="90px">
        <el-form-item label="变更事由" required>
          <el-input v-model="opReason" :rows="3" maxlength="255" placeholder="业务依据（审批人据此核对），必填" show-word-limit
                    type="textarea"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="submitting" @click="opVisible = false">取消</el-button>
          <el-button :loading="submitting" type="primary" @click="onSubmitOp">提交变更单</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 公司详情（行点击） -->
    <el-dialog v-model="detailVisible" :title="`公司详情：${detail?.companyName || ''}`" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="统一社会信用代码">{{ detail?.uscc || '-' }}</el-descriptions-item>
        <el-descriptions-item label="公司名称">{{ detail?.companyName }}</el-descriptions-item>
        <el-descriptions-item label="主体O">{{ detail?.subjectO || '-' }}</el-descriptions-item>
        <el-descriptions-item label="算法">{{ detail?.algorithm || '-' }}</el-descriptions-item>
        <el-descriptions-item label="公司状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
        <el-descriptions-item label="根密钥状态">{{
            detail?.rootKeyState ? rootKeyText(detail.rootKeyState) : '-'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="根CA有效期">{{
            detail ? validity(detail.rootValidFrom, detail.rootValidTo) : ''
          }}
        </el-descriptions-item>
        <el-descriptions-item label="二级CA有效期（SUB_CA）">
          {{ detail ? validity(detail.subCaValidFrom, detail.subCaValidTo) : '' }}
        </el-descriptions-item>
        <el-descriptions-item label="联系人">{{ detail?.contact || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系方式">{{ maskPhone(detail?.contactPhone) }}</el-descriptions-item>
        <el-descriptions-item label="接入ID（App ID）">
          <span v-if="detail?.apiAppId" class="cell-mono">{{ detail.apiAppId }}</span>
          <span v-else>未生成</span>
        </el-descriptions-item>
        <el-descriptions-item label="接入状态">
          {{ detail?.apiAppId ? (detail.apiClientStatus === 1 ? '启用' : '停用') : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="说明">{{ detail?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail?.createdAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="密钥保管人">
          <span v-if="holdersList.length">{{ holderNames }}</span>
          <span v-else>尚未指定</span>
        </el-descriptions-item>
      </el-descriptions>

      <template v-if="myShareData">
        <el-divider content-position="left">我的密钥份额（仅本人可见）</el-divider>
        <el-alert :closable="false" class="dialog-tip" show-icon title="这是你本人持有的根私钥门限份额，请离线保管；重建二级CA 时需粘贴 ≥3 份份额加载根私钥。"
                  type="warning"/>
        <el-input :model-value="myShareData.shareJson" :rows="6" class="share-view" readonly type="textarea"/>
        <div style="margin-top: 8px; display: flex; gap: 8px">
          <el-button size="small" type="primary" @click="onCopy(myShareData.shareJson)">复制我的份额</el-button>
          <el-button size="small" @click="onDownloadMyShare">下载我的份额(JSON)</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 重新指派密钥保管人（发起变更单：原 5 名保管人投票 >3 同意后执行，不直接绑定） -->
    <el-dialog v-model="reassignVisible" :close-on-click-modal="false" title="重新指派密钥保管人" width="680px">
      <el-alert :closable="false" class="dialog-tip" show-icon
                title="提交后由该公司原 5 名密钥保管人在「公司变更审批」页逐人投票，同意数超过 3（≥4）自动执行重绑；发起人本人不可作为新保管人。"
                type="warning"/>
      <el-descriptions :column="1" border class="op-desc">
        <el-descriptions-item label="目标公司">{{ reassignTarget?.companyName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="统一社会信用代码"><span class="cell-mono">{{ reassignTarget?.uscc || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前状态">{{ reassignTarget ? statusText(reassignTarget.status) : '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-form class="op-form" label-width="120px">
        <el-form-item label="新保管人(5人)" required>
          <el-select v-model="reassignHolders" :multiple-limit="5" clearable collapse-tags collapse-tags-tooltip filterable
                     multiple placeholder="指定 5 名新根私钥份额保管人（须为密钥管理员）" style="width: 100%">
            <el-option v-for="u in simpleUsers" :key="u.username"
                       :disabled="u.status !== 1"
                       :label="`${u.realName || u.username}（${u.username}${u.companyUscc ? '' : ' · 平台级'}）`" :value="u.username"/>
          </el-select>
        </el-form-item>
        <el-form-item label="变更事由" required>
          <el-input v-model="reassignReason" :rows="3" maxlength="255" placeholder="业务依据（原保管人投票时核对），必填" show-word-limit
                    type="textarea"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :disabled="reassigning" @click="reassignVisible = false">取消</el-button>
          <el-button :disabled="reassignHolders.length !== 5 || !reassignReason.trim()" :loading="reassigning" type="primary"
                     @click="onSubmitReassign">
            提交变更单
          </el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 接入凭证回执（仅展示一次） -->
    <el-dialog v-model="credVisible" :close-on-click-modal="false" title="接入凭证" width="620px" @closed="cred = null">
      <el-alert
          :closable="false"
          show-icon
          title="密钥仅展示一次，关闭后无法再次查看；请立即复制到 HIS 等外部系统的配置中。"
          type="warning"
      />
      <el-descriptions v-if="cred" :column="1" border class="cred-desc">
        <el-descriptions-item label="接入ID（App ID）" label-width="150px">
          <span class="cell-mono">{{ cred.appId }}</span>
          <el-button link size="small" type="primary" @click="onCopy(cred.appId)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="接入密钥">
          <span class="cell-mono">{{ cred.secret }}</span>
          <el-button link size="small" type="primary" @click="onCopy(cred.secret)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="绑定公司">{{ cred.companyName || cred.companyUscc }}</el-descriptions-item>
        <el-descriptions-item label="密钥版本">{{ cred.secretVersion ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="credVisible = false">我已保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {Plus, Refresh, Search} from '@element-plus/icons-vue'
import {
  createCompany,
  getShareOverview,
  listCompaniesCached,
  listSimpleUsers,
  pageCompanies,
  submitCompanyOp
} from '@/api/system'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'
import {hasPerm} from '@/lib/perm'
import {copyText} from '@/lib/clipboard'
import {downloadText} from '@/lib/download'

const OP_TYPE_LABELS = {
  SUSPEND: '暂停公司',
  RESUME: '恢复公司',
  CLOSE: '关闭公司',
  RESET_CREDENTIAL: '重置接入密钥',
  REASSIGN_HOLDERS: '重新指派保管人'
}

// ===== 公司列表 =====
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, status: '', keyword: ''})
const {queryCardRef, footerRef, tableMaxHeight} = useTableMaxHeight()

// ===== 弹窗 =====
const createVisible = ref(false)
const opVisible = ref(false)
const opType = ref('')
const opTarget = ref(null)
const opReason = ref('')
const submitting = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const holdersList = ref([])
const myShareData = ref(null)
// 详情只展示保管人姓名，账号不下发也不展示（可登录凭据的一半）
const holderNames = computed(() => holdersList.value.map(h => h.holderRealName).filter(Boolean).join('，'))
const credVisible = ref(false)
const cred = ref(null)
const formRef = ref()
const simpleUsers = ref([])
// 保管人候选下拉只服务「新建公司」与「重新指派保管人」两个入口，无这两个权限点就不发请求
const canPickHolders = computed(() => hasPerm('company:create') || hasPerm('ca:share:assign'))
const form = reactive({
  uscc: '',
  companyName: '',
  subjectO: '',
  algorithm: 'SM2',
  contact: '',
  contactPhone: '',
  description: '',
  shareHolders: []
})

const opTitle = computed(() => OP_TYPE_LABELS[opType.value] || '公司变更')

// ===== 统一社会信用代码（GB 32100）：18 位，末位为加权和 mod 31 的校验码 =====
const USCC_CHARSET = '0123456789ABCDEFGHJKLMNPQRTUWXY'
const USCC_WEIGHTS = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28]

function isValidUscc(code) {
  const s = String(code || '').toUpperCase()
  if (!/^[0-9A-HJ-NPQRTUWXY]{18}$/.test(s)) return false
  let sum = 0
  for (let i = 0; i < 17; i++) {
    const idx = USCC_CHARSET.indexOf(s[i])
    if (idx < 0) return false
    sum += idx * USCC_WEIGHTS[i]
  }
  return USCC_CHARSET[(31 - (sum % 31)) % 31] === s[17]
}

// 输入即规整：去空格、统一大写（后端仍按校验码复核）
function onUsccInput(val) {
  form.uscc = String(val || '').replace(/\s/g, '').toUpperCase()
}

const rules = {
  companyName: [{required: true, message: '请输入公司名称', trigger: 'blur'}],
  uscc: [
    {required: true, message: '请输入统一社会信用代码', trigger: 'blur'},
    {
      validator: (rule, val, cb) => (isValidUscc(val) ? cb() : cb(new Error('18 位代码或末位校验码不正确'))),
      trigger: 'blur'
    }
  ],
  subjectO: [{required: true, message: '请输入主体O', trigger: 'blur'}],
  contactPhone: [{
    validator: (rule, val, cb) => (!val || /^1[3-9]\d{9}$/.test(val) ? cb() : cb(new Error('请填写 11 位手机号'))),
    trigger: 'blur'
  }],
  shareHolders: [{
    validator: (rule, val, cb) => (Array.isArray(val) && val.length === 5 ? cb() : cb(new Error('必须指定恰好 5 名密钥保管人'))),
    trigger: 'change'
  }]
}

// 联系人手机号展示掩码（列表/详情；完整号码只在登记时录入，不再回显）
function maskPhone(v) {
  if (!v) return '-'
  const s = String(v)
  if (/^1[3-9]\d{9}$/.test(s)) return s.slice(0, 3) + '****' + s.slice(7)
  return s.length > 4 ? s.slice(0, 2) + '****' + s.slice(-2) : '****'
}

function statusText(s) {
  return {ACTIVE: '启用', SUSPENDED: '已暂停', CLOSED: '已关闭'}[s] || s || '-'
}

function statusTag(s) {
  return {ACTIVE: 'success', SUSPENDED: 'warning', CLOSED: 'info'}[s] || 'info'
}

function rootKeyText(s) {
  return {
    ONLINE: 'ONLINE 在线',
    SEALED: 'SEALED 封存',
    THRESHOLD: 'THRESHOLD 门限分持',
    SHARES: 'SHARES 门限分持'
  }[s] || s
}

function rootKeyTag(s) {
  return {ONLINE: 'success', SEALED: 'warning', THRESHOLD: 'primary', SHARES: 'primary'}[s] || 'info'
}

function validity(from, to) {
  if (!from && !to) return '-'
  return `${from || '?'} ~ ${to || '?'}`
}

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
  loadDetailShares(row.uscc)
}

// 详情：拉取该公司密钥份额总览（一次调用返回保管人名单 + 当前登录人自身份额）
async function loadDetailShares(uscc) {
  holdersList.value = []
  myShareData.value = null
  try {
    const res = await getShareOverview(uscc)
    const d = res.data || {}
    holdersList.value = d.holders || []
    myShareData.value = d.mine || null
  } catch (e) {
    // 错误提示已由拦截器统一处理
  }
}

function onDownloadMyShare() {
  if (!myShareData.value) return
  downloadText(myShareData.value.shareJson, `csssl-root-share-${detail.value?.uscc || 'mine'}-${myShareData.value.index}.json`)
  ElMessage.success('我的份额已导出，请离线保管')
}

// 重新指派保管人（发起变更单：原 5 名保管人投票 >3 同意后执行；不再直接 assignShares）
const reassignVisible = ref(false)
const reassignTarget = ref(null)
const reassignHolders = ref([])
const reassignReason = ref('')
const reassigning = ref(false)

async function openReassignFlow(row) {
  reassignTarget.value = row
  reassignHolders.value = []
  reassignReason.value = ''
  // 候选收敛为「平台级 + 该公司本域」的密钥管理员（服务端按 companyUscc 过滤）
  await loadSimpleUsers(row.uscc)
  reassignVisible.value = true
}

async function onSubmitReassign() {
  const reason = reassignReason.value.trim()
  if (reassignHolders.value.length !== 5) {
    ElMessage.warning('必须指定恰好 5 名新保管人')
    return
  }
  if (!reason) {
    ElMessage.warning('请填写变更事由')
    return
  }
  reassigning.value = true
  try {
    await submitCompanyOp({
      opType: 'REASSIGN_HOLDERS',
      uscc: reassignTarget.value.uscc,
      reason,
      newHolders: reassignHolders.value
    })
    ElMessage.success('重新指派保管人变更单已提交，待原 5 名保管人在「公司变更审批」页投票（>3 同意后执行）')
    reassignVisible.value = false
    await afterSubmit()
  } finally {
    reassigning.value = false
  }
}

// 公司列表操作：全部是「提交变更单」，不直接执行；按钮全部平铺，不再收进「更多」下拉
function companyActions(row) {
  const acts = [{label: '详情', run: () => onRowDetail(row)}]
  // 重新指派保管人：仅公司管理员（ca:share:assign）；提交后由原保管人投票决定，不直接绑定
  if (row.status === 'ACTIVE' && hasPerm('ca:share:assign')) {
    acts.push({label: '重新指派保管人', type: 'primary', run: () => openReassignFlow(row)})
  }
  if (!hasPerm('company:op:apply')) return acts
  if (row.status === 'ACTIVE') {
    acts.push({
      label: row.apiAppId ? '申请重置密钥' : '申请生成凭证',
      type: 'primary',
      run: () => openOp('RESET_CREDENTIAL', row)
    })
    acts.push({label: '申请暂停', type: 'warning', run: () => openOp('SUSPEND', row)})
  }
  if (row.status === 'SUSPENDED') {
    acts.push({label: '申请恢复', type: 'success', run: () => openOp('RESUME', row)})
  }
  if (row.status === 'CLOSED') {
    // 关闭只停服务不动密钥，同码不能再新建（唯一键占位），要重新服务这家机构只能恢复
    acts.push({label: '申请恢复', type: 'success', run: () => openOp('RESUME', row)})
  }
  if (row.status === 'ACTIVE') {
    acts.push({label: '申请关闭', type: 'danger', run: () => openOp('CLOSE', row)})
  }
  return acts
}

async function onCopy(text) {
  const ok = await copyText(text)
  ok ? ElMessage.success('已复制') : ElMessage.error('复制失败，请手动选择复制')
}

function showCredential(payload) {
  cred.value = {
    appId: payload.appId,
    secret: payload.secret,
    companyUscc: payload.companyUscc,
    companyName: payload.companyName,
    secretVersion: payload.secretVersion
  }
  credVisible.value = true
}

// ===== 公司列表加载 =====
async function load() {
  loading.value = true
  try {
    const res = await pageCompanies({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      status: query.status || undefined,
      keyword: query.keyword.trim() || undefined
    })
    rows.value = res.data?.records || []
    total.value = res.data?.total || 0
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

// ===== 新建公司（直接执行：建公司即建根并发放接入凭证） =====
function openCreate() {
  Object.assign(form, {
    uscc: '',
    companyName: '',
    subjectO: '',
    algorithm: 'SM2',
    contact: '',
    contactPhone: '',
    description: '',
    shareHolders: []
  })
  formRef.value?.clearValidate()
  createVisible.value = true
}

async function onCreate() {
  await formRef.value.validate()
  submitting.value = true
  try {
    const res = await createCompany({
      uscc: form.uscc,
      companyName: form.companyName,
      subjectO: form.subjectO,
      algorithm: form.algorithm,
      contact: form.contact,
      contactPhone: form.contactPhone,
      description: form.description,
      shareHolders: form.shareHolders
    })
    createVisible.value = false
    ElMessage.success('公司已创建，独立根CA与SUB_CA已初始化，已指定 5 名密钥保管人')
    showCredential(res.data || {})
    await Promise.all([load(), listCompaniesCached(true)])
  } finally {
    submitting.value = false
  }
}

// ===== 发起变更（暂停/恢复/关闭/重置接入密钥） =====
function openOp(type, row) {
  opType.value = type
  opTarget.value = row
  opReason.value = ''
  opVisible.value = true
}

async function onSubmitOp() {
  const reason = opReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写变更事由')
    return
  }
  submitting.value = true
  try {
    await submitCompanyOp({
      opType: opType.value,
      uscc: opTarget.value.uscc,
      reason
    })
    ElMessage.success(`${opTitle.value}变更单已提交，等待公司审批员批准`)
    opVisible.value = false
    await afterSubmit()
  } finally {
    submitting.value = false
  }
}

// 提交后刷新列表；公司状态在审批通过执行后才会变化
async function afterSubmit() {
  await load()
}

onMounted(async () => {
  await load()
  if (canPickHolders.value) {
    await loadSimpleUsers()
  }
})

// 拉取保管人候选（仅密钥管理员 KEY_ADMIN）。
// companyUscc 为空 → 只取平台级账号（新建公司时用：本公司账号此刻还不存在）；
// 传入公司码 → 取「平台级 + 该公司本域」（重新指派保管人时用）。
async function loadSimpleUsers(companyUscc) {
  try {
    const res = await listSimpleUsers(companyUscc)
    // 服务端已按角色+公司域过滤；前端再兜底过滤一次，并保留禁用账号（置灰不可选）
    simpleUsers.value = (res.data || []).filter(u => u.roleCode === 'KEY_ADMIN')
  } catch (e) {
    simpleUsers.value = []
  }
}
</script>

<style scoped>
.font-medium {
  font-weight: 500;
  color: #1e293b;
}

.dialog-tip {
  margin-bottom: 14px;
}

.op-desc {
  margin-bottom: 12px;
}

.cred-desc {
  margin-top: 12px;
}

.form-tip {
  font-size: 12px;
  line-height: 1.5;
  color: #8a94a6;
  margin-top: 6px;
}

.form-tip-alert {
  margin-top: 8px;
}

.share-view :deep(.el-textarea__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: #1e293b;
  background: #f7f9fc;
}
</style>
