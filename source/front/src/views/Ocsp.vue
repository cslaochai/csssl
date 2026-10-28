<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="section-title">OCSP 证书状态自助查询</div>
      </div>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="本服务 OCSP 接口为标准 RFC 6960 二进制（DER）协议，不支持 JSON 简化查询——为避免误报结果，页面不代为发起协议交互；下方按证书序列号生成可直接执行的 openssl/curl 查询与解析示例。"
        style="margin-bottom: 14px"
      />

      <el-descriptions :column="1" border size="small" style="margin-bottom: 16px">
        <el-descriptions-item label="OCSP 端点（POST，DER）">/ssl/ca/public/ocsp（Content-Type: application/ocsp-request）</el-descriptions-item>
        <el-descriptions-item label="OCSP 端点（GET，base64url DER）">/ssl/ca/public/ocsp/{请求DER的base64url}</el-descriptions-item>
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

      <div class="section-title" style="margin: 6px 0 10px">查询示例（自动填充）</div>
      <pre class="cmd-block">{{ sampleScript }}</pre>
      <div style="margin-top: 12px">
        <el-button type="primary" plain @click="onCopy">复制示例</el-button>
        <el-button plain @click="onDownload">下载示例脚本 .sh</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadText } from '@/lib/download'

const serialHex = ref('')
const issuer = ref('subca')

const OCSP_URL = 'http://<CA主机>:8092/ssl/ca/public/ocsp'
const ISSUER_URL = computed(() =>
  issuer.value === 'subca' ? 'http://<CA主机>:8092/ssl/ca/public/subca' : 'http://<CA主机>:8092/ssl/ca/public/root'
)
const SERIAL = computed(() => (serialHex.value.trim() ? `0x${serialHex.value.trim().replace(/^0x/i, '')}` : '0x<证书序列号HEX>'))

const sampleScript = computed(() => `#!/usr/bin/env bash
# CSSSL OCSP（RFC 6960 DER）自助查询示例
set -euo pipefail

OCSP_URL="${OCSP_URL}"
ISSUER_PEM="${issuer.value}.pem"
SERIAL="${SERIAL.value}"

# 1) 获取签发者证书（二级CA 或根CA）
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
# 浏览器直连（GET）：先将 req.der 做 base64url 编码（openssl base64 | tr '+/' '-_' | tr -d '=\\n'），
# 再访问 ${OCSP_URL}/<编码结果>`
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
  downloadText(sampleScript.value, 'ocsp-query-example.sh')
  ElMessage.success('示例脚本已下载')
}
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #1d2a4a;
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
</style>
