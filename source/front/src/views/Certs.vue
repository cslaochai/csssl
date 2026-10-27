<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="filters">
          <el-select v-model="query.certType" placeholder="证书类型" clearable style="width: 130px" @change="reload">
            <el-option label="服务端" value="SERVER" />
            <el-option label="客户端" value="CLIENT" />
            <el-option label="二级CA" value="SUB_CA" />
          </el-select>
          <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="reload">
            <el-option label="有效" value="VALID" />
            <el-option label="已吊销" value="REVOKED" />
            <el-option label="已过期" value="EXPIRED" />
          </el-select>
          <el-input
            v-model="query.keyword"
            placeholder="CN / 序列号"
            clearable
            style="width: 220px"
            @keyup.enter="reload"
            @clear="reload"
          />
          <el-button type="primary" plain @click="reload">查询</el-button>
        </div>
        <div>
          <el-button type="primary" @click="openIssue">签发证书</el-button>
          <el-button @click="openCsr">CSR签发</el-button>
        </div>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="subjectCn" label="主体CN" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTag(row.certType)">{{ typeText(row.certType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="算法" width="90">
          <template #default="{ row }">{{ row.algorithm }}</template>
        </el-table-column>
        <el-table-column prop="serialHex" label="序列号" min-width="150" show-overflow-tooltip />
        <el-table-column label="有效期" width="300">
          <template #default="{ row }">{{ row.validFrom }} ~ {{ row.validTo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="用途说明" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onDownloadCert(row)">证书</el-button>
            <el-button link type="primary" size="small" @click="onDownloadKey(row)">私钥</el-button>
            <el-button
              link
              type="danger"
              size="small"
              :disabled="row.status !== 'VALID' || row.certType === 'ROOT'"
              @click="onRevoke(row)"
            >吊销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 14px; justify-content: flex-end"
        @change="reload"
      />
    </div>

    <!-- 签发证书 -->
    <el-dialog v-model="issueVisible" title="签发证书" width="520px" :close-on-click-modal="false">
      <el-form :model="issueForm" label-width="110px">
        <el-form-item label="主体CN" required>
          <el-input v-model="issueForm.subjectCn" placeholder="如 his.cslk.local / csmig 前置机" />
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
        <el-form-item label="SAN">
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
    <el-dialog v-model="csrVisible" title="CSR 签发（主体与公钥取自 CSR）" width="560px" :close-on-click-modal="false">
      <el-form :model="csrForm" label-width="110px">
        <el-form-item label="CSR 内容" required>
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
        <el-form-item label="SAN（可选）">
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

    <!-- 吊销 -->
    <el-dialog v-model="revokeVisible" title="吊销证书" width="460px" :close-on-click-modal="false">
      <p style="margin-bottom: 12px">
        确定吊销证书 <b>{{ revokeTarget?.subjectCn }}</b> 吗？吊销后该证书立即失效并进入 CRL。
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
          <el-button type="danger" :loading="submitting" @click="onRevokeConfirm">吊销</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageCerts,
  issueCert,
  issueCertByCsr,
  revokeCert,
  downloadCertPem,
  downloadKeyPem
} from '@/api/system'
import { downloadText } from '@/lib/download'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, certType: '', status: '', keyword: '' })

const issueVisible = ref(false)
const csrVisible = ref(false)
const revokeVisible = ref(false)
const revokeTarget = ref(null)
const revokeReason = ref('KEY_COMPROMISE')
const submitting = ref(false)

const defaultDays = 365

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
  return { VALID: '有效', REVOKED: '已吊销', EXPIRED: '已过期' }[s] || s
}
function statusTag(s) {
  return { VALID: 'success', REVOKED: 'danger', EXPIRED: 'info' }[s] || ''
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
  } finally {
    loading.value = false
  }
}

function reload() {
  query.pageNo = 1
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

function onRevoke(row) {
  revokeTarget.value = row
  revokeReason.value = 'KEY_COMPROMISE'
  revokeVisible.value = true
}

async function onRevokeConfirm() {
  submitting.value = true
  try {
    await revokeCert(revokeTarget.value.id, revokeReason.value)
    ElMessage.success('已吊销并加入CRL')
    revokeVisible.value = false
    load()
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

onMounted(load)
</script>

<style scoped>
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
