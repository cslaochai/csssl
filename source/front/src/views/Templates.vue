<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="filters">
          <el-input
            v-model="query.keyword"
            placeholder="模板编码/名称"
            clearable
            style="width: 220px"
            @keyup.enter="reload"
            @clear="reload"
          />
          <el-button type="primary" plain @click="reload">查询</el-button>
        </div>
        <div>
          <el-button type="primary" @click="openCreate">新建模板</el-button>
        </div>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="templateCode" label="编码" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.builtin === 1 ? 'warning' : ''">{{ row.templateCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="templateName" label="名称" min-width="140" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ typeText(row.certType) }}</template>
        </el-table-column>
        <el-table-column prop="algorithm" label="算法" width="90" />
        <el-table-column prop="validityDays" label="有效期(天)" width="110" />
        <el-table-column prop="keyUsage" label="KeyUsage" min-width="180" show-overflow-tooltip />
        <el-table-column prop="extKeyUsage" label="EKU" width="100">
          <template #default="{ row }">{{ row.extKeyUsage || '-' }}</template>
        </el-table-column>
        <el-table-column prop="policyOid" label="策略OID" min-width="170" show-overflow-tooltip />
        <el-table-column label="内置" width="70">
          <template #default="{ row }">{{ row.builtin === 1 ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" :disabled="row.builtin === 1" @click="onDelete(row)">删除</el-button>
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
        @change="load"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑模板' : '新建证书模板'" width="600px" :close-on-click-modal="false">
      <el-form :model="form" label-width="120px">
        <el-form-item label="模板编码" required>
          <el-input v-model="form.templateCode" :disabled="editing" placeholder="如 SERVER_COMM / DOCTOR_SIGN / 自定义编码" />
        </el-form-item>
        <el-form-item label="模板名称" required>
          <el-input v-model="form.templateName" placeholder="如 机构通信证书" />
        </el-form-item>
        <el-form-item label="证书类型" required>
          <el-radio-group v-model="form.certType">
            <el-radio-button value="SERVER">服务端</el-radio-button>
            <el-radio-button value="CLIENT">客户端</el-radio-button>
            <el-radio-button value="SUB_CA">二级CA</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="算法" required>
          <el-radio-group v-model="form.algorithm">
            <el-radio-button value="SM2">SM2 国密</el-radio-button>
            <el-radio-button value="RSA2048">RSA 2048</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期(天)" required>
          <el-input-number v-model="form.validityDays" :min="1" :max="36500" />
        </el-form-item>
        <el-form-item label="KeyUsage" required>
          <el-select v-model="form.keyUsageList" multiple style="width: 100%" placeholder="选择密钥用途">
            <el-option label="digitalSignature 数字签名" value="digitalSignature" />
            <el-option label="nonRepudiation 不可否认" value="nonRepudiation" />
            <el-option label="keyEncipherment 密钥加密" value="keyEncipherment" />
            <el-option label="keyCertSign 证书签名(CA)" value="keyCertSign" />
            <el-option label="cRLSign CRL签名(CA)" value="cRLSign" />
          </el-select>
        </el-form-item>
        <el-form-item label="EKU（SUB_CA留空）">
          <el-select v-model="form.extKeyUsage" clearable style="width: 100%" placeholder="扩展密钥用途">
            <el-option label="serverAuth 服务端认证" value="serverAuth" />
            <el-option label="clientAuth 客户端认证" value="clientAuth" />
          </el-select>
        </el-form-item>
        <el-form-item label="策略OID">
          <el-input v-model="form.policyOid" placeholder="如 1.2.156.112562.2.2.15（SM2签名证书策略）" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onSave">保存</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageTemplates, createTemplate, updateTemplate, deleteTemplate } from '@/api/system'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '' })
const dialogVisible = ref(false)
const editing = ref(false)
const submitting = ref(false)
const form = reactive({
  id: null, templateCode: '', templateName: '', certType: 'SERVER', algorithm: 'SM2',
  validityDays: 365, keyUsageList: [], extKeyUsage: '', policyOid: '', description: ''
})

function typeText(t) {
  return { SERVER: '服务端', CLIENT: '客户端', SUB_CA: '二级CA' }[t] || t
}

function fillForm(row) {
  Object.assign(form, {
    id: row.id, templateCode: row.templateCode, templateName: row.templateName, certType: row.certType,
    algorithm: row.algorithm, validityDays: row.validityDays,
    keyUsageList: row.keyUsage ? row.keyUsage.split(',').map(s => s.trim()) : [],
    extKeyUsage: row.extKeyUsage || '', policyOid: row.policyOid || '', description: row.description || ''
  })
}

async function load() {
  loading.value = true
  try {
    const res = await pageTemplates({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
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

function reload() {
  query.pageNo = 1
  load()
}

function openCreate() {
  editing.value = false
  fillForm({ id: null, templateCode: '', templateName: '', certType: 'SERVER', algorithm: 'SM2', validityDays: 365, keyUsage: '', extKeyUsage: '', policyOid: '', description: '' })
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = true
  fillForm(row)
  dialogVisible.value = true
}

async function onSave() {
  if (!form.templateCode.trim() || !form.templateName.trim()) {
    ElMessage.warning('请填写模板编码与名称')
    return
  }
  if (!form.keyUsageList.length) {
    ElMessage.warning('请至少选择一项 KeyUsage')
    return
  }
  const payload = {
    templateCode: form.templateCode,
    templateName: form.templateName,
    certType: form.certType,
    algorithm: form.algorithm,
    validityDays: String(form.validityDays),
    keyUsage: form.keyUsageList.join(','),
    extKeyUsage: form.extKeyUsage || '',
    policyOid: form.policyOid || '',
    description: form.description || ''
  }
  submitting.value = true
  try {
    if (editing.value) {
      await updateTemplate(form.id, payload)
      ElMessage.success('模板已更新')
    } else {
      await createTemplate(payload)
      ElMessage.success('模板创建成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除模板「${row.templateName}」吗？`, '提示', { type: 'warning' })
  await deleteTemplate(row.id)
  ElMessage.success('模板已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
