<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="section-title">OCSP 证书状态自助查询</div>
      </div>

      <!-- ===== 在线验证（JSON，按机构域） ===== -->
      <el-alert
        type="success"
        :closable="false"
        show-icon
        title="在线验证走公开端点 POST /ssl/ca/public/{orgKey}/verify（JSON：{certPem, purpose}），粘贴证书 PEM 即可返回 good / revoked / unknown 三态；该端点无需登录。"
        style="margin-bottom: 14px"
      />
      <el-form label-width="110px" style="max-width: 720px">
        <el-form-item label="机构域">
          <el-select v-model="orgKey" style="width: 240px" placeholder="选择机构域">
            <el-option label="default（默认域）" value="default" />
            <el-option
              v-for="o in orgOptions"
              :key="o.orgKey"
              :label="o.orgName ? `${o.orgKey}（${o.orgName}）` : o.orgKey"
              :value="o.orgKey"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="验证用途">
          <el-select v-model="purpose" clearable style="width: 240px" placeholder="可选：限定 EKU 口径">
            <el-option label="serverAuth（服务端）" value="serverAuth" />
            <el-option label="clientAuth（客户端）" value="clientAuth" />
          </el-select>
        </el-form-item>
        <el-form-item label="证书PEM">
          <el-input
            v-model="certPem"
            type="textarea"
            :rows="8"
            placeholder="-----BEGIN CERTIFICATE-----&#10;...&#10;-----END CERTIFICATE-----"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="verifying" @click="onVerify">在线验证</el-button>
        </el-form-item>
      </el-form>

      <template v-if="result">
        <el-result
          v-if="resultState === 'good'"
          icon="success"
          title="状态：good（有效）"
          :sub-title="`机构域 ${resultOrg} · 证书未被吊销且链可校验`"
        />
        <el-result
          v-else-if="resultState === 'revoked'"
          icon="error"
          title="状态：revoked（已吊销）"
          :sub-title="`机构域 ${resultOrg} · 该证书已进入机构 CRL，请勿继续信任`"
        />
        <el-result
          v-else-if="resultState === 'unknown'"
          icon="warning"
          title="状态：unknown（未知）"
          :sub-title="`机构域 ${resultOrg} · 证书不在该机构签发范围或序列号未命中`"
        />
        <template v-else>
          <el-alert type="info" :closable="false" show-icon :title="`验证返回（机构域 ${resultOrg}）`" style="margin-bottom: 8px" />
        </template>
        <el-descriptions :column="1" border size="small" style="max-width: 720px">
          <el-descriptions-item v-for="(v, k) in resultFields" :key="k" :label="k">
            <div class="mono-wrap">{{ formatVal(v) }}</div>
          </el-descriptions-item>
        </el-descriptions>
      </template>

      <el-divider content-position="left">标准协议交互（RFC 6960 DER，供设备/中间件直连）</el-divider>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="OCSP 标准接口为二进制（DER）协议，浏览器不代为发起协议交互——下方按所选机构域与证书序列号生成可直接执行的 openssl/curl 查询与解析示例（URL 模板含 {orgKey}）。"
        style="margin-bottom: 14px"
      />
      <el-descriptions :column="1" border size="small" style="margin-bottom: 16px">
        <el-descriptions-item label="OCSP 端点（POST，DER）">/ssl/ca/public/{orgKey}/ocsp（Content-Type: application/ocsp-request）</el-descriptions-item>
        <el-descriptions-item label="OCSP 端点（GET，base64url DER）">/ssl/ca/public/{orgKey}/ocsp/{请求DER的base64url}</el-descriptions-item>
        <el-descriptions-item label="机构证书/CRL 公开端点">/ssl/ca/public/{orgKey}/root · /subca · /crl · /crl/delta</el-descriptions-item>
        <el-descriptions-item label="返回状态">good / revoked / unknown（挂起证书返回 on-hold 扩展）</el-descriptions-item>
      </el-descriptions>

      <el-form inline>
        <el-form-item label="证书序列号(HEX)">
          <el-input
            v-model="serialHex"
            placeholder="如 1A2B3C4D5E6F（证书详情表 serialHex 列）"
            style="width: 260px"
            clearable
          />
        </el-form-item>
        <el-form-item label="签发者">
          <el-radio-group v-model="issuer">
            <el-radio-button value="subca">二级CA（默认）</el-radio-button>
            <el-radio-button value="root">根CA</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <div class="section-title" style="margin: 6px 0 10px">查询示例（按机构域 {{ orgKey }} 自动填充）</div>
      <pre class="cmd-block">{{ sampleScript }}</pre>
      <div style="margin-top: 12px">
        <el-button type="primary" plain @click="onCopy">复制示例</el-button>
        <el-button plain @click="onDownload">下载示例脚本 .sh</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadText } from '@/lib/download'
import { verifyCertPublic, listOrgsCached } from '@/api/system'
import { currentOrg } from '@/lib/org'

// ===== 在线验证（POST /ca/public/{orgKey}/verify） =====
const orgKey = ref(currentOrg())
const orgOptions = ref([])
const certPem = ref('')
const purpose = ref('')
const verifying = ref(false)
const result = ref(null)
const resultOrg = ref('')

onMounted(async () => {
  orgOptions.value = await listOrgsCached()
})

const resultState = computed(() => {
  const d = result.value
  if (!d) return ''
  const s = d.status || d.certStatus || d.state || (typeof d === 'string' ? d : '')
  return String(s).toLowerCase()
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

async function onVerify() {
  if (!certPem.value.trim()) {
    ElMessage.warning('请粘贴证书 PEM')
    return
  }
  verifying.value = true
  try {
    const res = await verifyCertPublic(orgKey.value, {
      certPem: certPem.value.trim(),
      purpose: purpose.value || undefined
    })
    result.value = res.data
    resultOrg.value = orgKey.value
  } catch (e) {
    result.value = null
    // 机构无根/证书不属该域等错误由拦截器提示
  } finally {
    verifying.value = false
  }
}

// ===== DER 协议示例（URL 模板含 {orgKey}） =====
const serialHex = ref('')
const issuer = ref('subca')

const OCSP_URL = computed(() => `http://<CA主机>:8092/ssl/ca/public/${orgKey.value}/ocsp`)
const ISSUER_URL = computed(() => `http://<CA主机>:8092/ssl/ca/public/${orgKey.value}/${issuer.value}`)
const SERIAL = computed(() => (serialHex.value.trim() ? `0x${serialHex.value.trim().replace(/^0x/i, '')}` : '0x<证书序列号HEX>'))

const sampleScript = computed(() => `#!/usr/bin/env bash
# CSSSL OCSP（RFC 6960 DER）自助查询示例 —— 机构域 ${orgKey.value}
set -euo pipefail

OCSP_URL="${OCSP_URL.value}"
ISSUER_PEM="${issuer.value}.pem"
SERIAL="${SERIAL.value}"

# 1) 获取该机构签发者证书（二级CA 或根CA）：/ssl/ca/public/{orgKey}/root|subca
curl -s "${ISSUER_URL.value}" -o "$ISSUER_PEM"

# 2) 一步法：openssl 自带 OCSP 客户端（构造 DER 请求并解析响应）
openssl ocsp -issuer "$ISSUER_PEM" -serial "$SERIAL" \\
  -url "$OCSP_URL" -nonce -text

# 3) 分步法（验证 curl 通道）：先生成 DER 请求，再 POST，最后本地解析
openssl ocsp -issuer "$ISSUER_PEM" -serial "$SERIAL" -reqout req.der
curl -s -X POST "$OCSP_URL" \\
  -H 'Content-Type: application/ocsp-request' \\
  --data-binary @req.der -o resp.der
openssl ocsp -issuer "$ISSUER_PEM" -respin resp.der -noverify -text

# 结果判读：响应中 "Cert ID: ... Serial Nr: ... good/revoked/unknown"
# CRL 通道：curl -s "http://<CA主机>:8092/ssl/ca/public/${orgKey.value}/crl" -o crl.pem
# 浏览器直连（GET）：先将 req.der 做 base64url 编码（openssl base64 | tr '+/' '-_' | tr -d '=\\n'），
# 再访问 ${OCSP_URL.value}/<编码结果>`
)

async function onCopy() {
  try {
    await navigator.clipboard.writeText(sampleScript.value)
    ElMessage.success('示例已复制')
  } catch (e) {
    ElMessage.warning('浏览器不允许自动复制，请手动选中复制')
  }
}

function onDownload() {
  downloadText(sampleScript.value, `ocsp-query-${orgKey.value}.sh`)
  ElMessage.success('示例脚本已下载')
}
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title);
}
.cmd-block {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px 14px;
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  color: #303133;
  overflow-x: auto;
  white-space: pre;
}
.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
