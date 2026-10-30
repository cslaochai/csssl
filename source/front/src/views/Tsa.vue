<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="section-title">可信时间戳（TSA）服务</div>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-descriptions v-if="info.publicKeyPem" :column="2" border>
        <el-descriptions-item label="密钥算法">
          <el-tag size="small">{{ info.algorithm }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="签名算法">
          <el-tag size="small" type="warning">{{ info.signAlgo }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="公钥指纹（SM3）" :span="2">
          <div class="mono-wrap">{{ info.fingerprint }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="服务说明" :span="2">{{ info.note }}</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else-if="!loading" description="未获取到 TSA 公钥（首次盖章时自动生成）"/>

      <template v-if="info.publicKeyPem">
        <div class="section-title" style="margin: 16px 0 10px">验签公钥（PEM / SPKI）</div>
        <el-input :model-value="info.publicKeyPem" type="textarea" :rows="8" readonly class="pem-preview"/>
        <div style="margin-top: 12px">
          <el-button type="primary" plain @click="onDownloadPem">下载公钥 PEM</el-button>
          <el-button plain @click="onCopyPem">复制公钥</el-button>
        </div>
      </template>
    </div>

    <div class="page-card">
      <div class="section-title">业务系统对接示例</div>
      <pre class="cmd-block"># 1. 获取 TSA 验签公钥（匿名可取）
curl -s http://&lt;CA主机&gt;:8092/ssl/tsa/publicKey

# 2. 对内容摘要盖时间戳（SHA-256/SM3 摘要的 64 位十六进制）
curl -s -X POST http://&lt;CA主机&gt;:8092/ssl/tsa/stamp \
  -H 'Content-Type: application/json' \
  -d '{"digestHex": "&lt;64位十六进制摘要&gt;"}'

# 3. 验证令牌：用本页公钥对令牌内摘要字段做 SM3withSM2 验签，
#    并核对令牌时间与盖章返回 tsaTime、序列号一致。</pre>
    </div>
  </div>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {getTsaPublicKey} from '@/api/system'
import {downloadText} from '@/lib/download'

const loading = ref(false)
const info = ref({})

async function load() {
  loading.value = true
  try {
    const res = await getTsaPublicKey()
    info.value = res.data || {}
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}

function onDownloadPem() {
  downloadText(info.value.publicKeyPem, 'tsa-public-key.pem')
  ElMessage.success('TSA 公钥已下载')
}

async function onCopyPem() {
  try {
    await navigator.clipboard.writeText(info.value.publicKeyPem)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    ElMessage.warning('浏览器不允许自动复制，请手动选中文本复制')
  }
}

onMounted(load)
</script>

<style scoped>
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--his-title);
}

.pem-preview {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}

.mono-wrap {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  word-break: break-all;
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
