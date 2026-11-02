<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="section-title">OCSP 证书状态查询</div>
      </div>

      <el-form label-width="110px" style="max-width: 720px">
        <el-form-item label="公司域">
          <el-select v-model="companyUscc" placeholder="选择公司域" style="width: 240px">
            <el-option
                v-for="o in companyOptions"
                :key="o.uscc"
                :label="o.companyName ? `${o.companyName}（${o.uscc}）` : o.uscc"
                :value="o.uscc"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="验证用途">
          <el-select v-model="purpose" clearable placeholder="可选：限定 EKU 口径" style="width: 240px">
            <el-option label="服务端认证（serverAuth）" value="serverAuth"/>
            <el-option label="客户端/签名（clientAuth）" value="clientAuth"/>
          </el-select>
        </el-form-item>
        <el-form-item label="证书 PEM">
          <el-input
              v-model="certPem"
              :rows="8"
              placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
              type="textarea"
          />
        </el-form-item>
        <el-form-item>
          <el-button :loading="verifying" type="primary" @click="onVerify">查询</el-button>
        </el-form-item>
      </el-form>

      <template v-if="result">
        <el-result :icon="verdict.icon" :sub-title="verdict.subTitle" :title="verdict.title"/>
        <el-descriptions :column="1" border size="small" style="max-width: 720px">
          <el-descriptions-item v-for="(v, k) in resultFields" :key="k" :label="fieldLabel(k)">
            <div class="mono-wrap">{{ formatVal(v) }}</div>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </div>
  </div>
</template>

<script setup>
import {computed, onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {listCompaniesCached, verifyCertPublic} from '@/api/system'

const companyUscc = ref('')
const companyOptions = ref([])
const certPem = ref('')
const purpose = ref('')
const verifying = ref(false)
const result = ref(null)

onMounted(async () => {
  companyOptions.value = await listCompaniesCached()
  // 缺省选中首个启用公司，避免空域提交
  const active = companyOptions.value.find(o => o.status === 'ACTIVE') || companyOptions.value[0]
  companyUscc.value = active?.uscc || ''
})

/** 三态（后端返回，对齐 OCSP）：good / revoked / unknown */
const resultState = computed(() => {
  const d = result.value
  if (!d) return ''
  return String(d.status || d.certStatus || d.state || '').toLowerCase()
})

/**
 * 判定结论：status 只管吊销维度，valid 才管全链（验签+有效期+用途）。
 * 因此「已过期但未吊销」是 status=good / valid=false，不能只按 status 显示"有效"。
 */
const verdict = computed(() => {
  const d = result.value || {}
  const state = resultState.value
  const msg = d.message || ''
  if (d.valid === true) {
    return {icon: 'success', title: '校验通过', subTitle: '证书可信、未过期、未吊销'}
  }
  if (state === 'revoked') {
    return {icon: 'error', title: '状态：revoked（已吊销）', subTitle: msg || '该证书已进入 CRL，请勿继续信任'}
  }
  if (state === 'unknown') {
    return {icon: 'warning', title: '状态：unknown（未知）', subTitle: msg || '不在本公司签发范围或序列号未命中'}
  }
  return {icon: 'error', title: '校验不通过', subTitle: msg || '证书未通过链校验'}
})

const resultFields = computed(() => {
  const d = result.value
  if (!d || typeof d !== 'object') return {}
  const out = {}
  for (const [k, v] of Object.entries(d)) {
    if (v === null || v === undefined || v === '') continue
    out[k] = v
  }
  return out
})

function formatVal(v) {
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

// 验证返回字段：中文（英文键名），未收录的键原样展示
const FIELD_LABELS = {
  valid: '校验结论（valid）',
  status: '状态三态（status）',
  statusText: '状态说明（statusText）',
  message: '校验信息（message）',
  serialHex: '序列号（serialHex）',
  subjectCn: '主体（subjectCn）',
  issuerCn: '签发者（issuerCn）',
  algorithm: '算法（algorithm）',
  validFrom: '生效时间（validFrom）',
  validTo: '失效时间（validTo）'
}

function fieldLabel(k) {
  return FIELD_LABELS[k] || k
}

async function onVerify() {
  if (!certPem.value.trim()) {
    ElMessage.warning('请粘贴证书 PEM')
    return
  }
  verifying.value = true
  try {
    const res = await verifyCertPublic(companyUscc.value, {
      certPem: certPem.value.trim(),
      purpose: purpose.value || undefined
    })
    result.value = res.data
  } catch (e) {
    result.value = null
    // 公司无根/证书不属该域等错误由拦截器提示
  } finally {
    verifying.value = false
  }
}
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title);
}

.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
