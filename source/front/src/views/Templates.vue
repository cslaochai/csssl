<template>
  <div>
    <!-- 查询卡 -->
    <el-card ref="queryCardRef" class="query-card" shadow="never">
      <div class="query-wrap">
        <el-form :model="query" inline>
          <el-form-item label="关键字">
            <el-input
              v-model="query.keyword"
              placeholder="模板编码/名称"
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
          <el-button v-perm="'template:create'" type="primary" :icon="Plus" @click="openCreate">新建模板</el-button>
        </div>
      </div>
    </el-card>

    <!-- 表格卡 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" :max-height="tableMaxHeight" stripe style="width: 100%">
        <el-table-column prop="templateCode" label="编码" class-name="cell-mono" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.builtin === 1 ? 'warning' : 'info'">{{ row.templateCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="templateName" label="名称" min-width="140" />
        <el-table-column label="归属" width="110">
          <template #default="{ row }">
            <el-tag v-if="!row.tenantUscc" size="small" type="info">全局</el-tag>
            <el-tag v-else size="small" type="warning">{{ tenantNames[row.tenantUscc] || row.tenantUscc }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ typeText(row.certType) }}</template>
        </el-table-column>
        <el-table-column prop="algorithm" label="算法" width="90" />
        <el-table-column prop="validityDays" label="有效期(天)" width="110" />
        <el-table-column prop="keyUsage" label="密钥用途（KeyUsage）" min-width="180" show-overflow-tooltip />
        <el-table-column prop="extKeyUsage" label="扩展用途（EKU）" width="130">
          <template #default="{ row }">{{ row.extKeyUsage || '-' }}</template>
        </el-table-column>
        <el-table-column prop="policyOid" label="策略OID" min-width="170" show-overflow-tooltip />
        <el-table-column label="内置" width="70">
          <template #default="{ row }">{{ row.builtin === 1 ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column align="right" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onRowDetail(row)">详情</el-button>
            <el-button v-perm="'template:update'" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-perm="'template:delete'" link type="danger" size="small" :disabled="row.builtin === 1" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="rows.length === 0 && !loading" class="empty-tip">暂无匹配的模板记录</div>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑模板' : '新建证书模板'" width="600px" :close-on-click-modal="false">
      <el-form :model="form" label-width="120px">
        <el-form-item label="模板编码" required>
          <el-input v-model="form.templateCode" :disabled="editing" placeholder="如 SERVER_COMM / DOCTOR_SIGN / 自定义编码" />
        </el-form-item>
        <el-form-item label="模板名称" required>
          <el-input v-model="form.templateName" placeholder="如 租户通信证书" />
        </el-form-item>
        <el-form-item v-if="!editing" label="归属">
          <el-radio-group v-model="form.tenantScope">
            <el-radio-button value="GLOBAL">全局模板</el-radio-button>
            <el-radio-button value="ORG">指定租户</el-radio-button>
          </el-radio-group>
          <el-select
            v-if="form.tenantScope === 'ORG'"
            v-model="form.tenantUscc"
            placeholder="选择租户"
            style="width: 100%; margin-top: 8px"
          >
            <el-option
              v-for="o in tenantOptions"
              :key="o.uscc"
              :label="o.tenantName ? `${o.tenantName}（${o.uscc}）` : o.uscc"
              :value="o.uscc"
            />
          </el-select>
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
        <el-form-item label="密钥用途（KeyUsage）" required>
          <el-select v-model="form.keyUsageList" multiple style="width: 100%" placeholder="选择密钥用途">
            <el-option label="数字签名（digitalSignature）" value="digitalSignature" />
            <el-option label="不可否认（nonRepudiation）" value="nonRepudiation" />
            <el-option label="密钥加密（keyEncipherment）" value="keyEncipherment" />
            <el-option label="证书签名-CA（keyCertSign）" value="keyCertSign" />
            <el-option label="吊销列表签名-CA（cRLSign）" value="cRLSign" />
          </el-select>
        </el-form-item>
        <el-form-item label="扩展用途（EKU，二级CA留空）">
          <el-select v-model="form.extKeyUsage" clearable style="width: 100%" placeholder="扩展密钥用途">
            <el-option label="服务端认证（serverAuth）" value="serverAuth" />
            <el-option label="客户端认证（clientAuth）" value="clientAuth" />
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

    <!-- 模板详情（行点击） -->
    <el-dialog v-model="detailVisible" :title="`模板详情：${detail?.templateName || ''}`" width="560px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="编码">{{ detail?.templateCode }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ detail?.templateName }}</el-descriptions-item>
        <el-descriptions-item label="归属">{{ tenantNames[detail?.tenantUscc] || detail?.tenantUscc || '全局模板' }}</el-descriptions-item>
        <el-descriptions-item label="证书类型">{{ detail ? typeText(detail.certType) : '' }}</el-descriptions-item>
        <el-descriptions-item label="算法">{{ detail?.algorithm }}</el-descriptions-item>
        <el-descriptions-item label="有效期(天)">{{ detail?.validityDays }}</el-descriptions-item>
        <el-descriptions-item label="密钥用途（KeyUsage）">{{ detail?.keyUsage || '-' }}</el-descriptions-item>
        <el-descriptions-item label="扩展用途（EKU）">{{ detail?.extKeyUsage || '-' }}</el-descriptions-item>
        <el-descriptions-item label="策略OID">{{ detail?.policyOid || '-' }}</el-descriptions-item>
        <el-descriptions-item label="内置模板">{{ detail?.builtin === 1 ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="说明">{{ detail?.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ detail?.createdAt || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { pageTemplates, createTemplate, updateTemplate, deleteTemplate, tenantNameMap, listTenantsCached } from '@/api/system'
import { PAGE_SIZES, DEFAULT_PAGE_SIZE } from '@/lib/pagination'
import { useTableMaxHeight } from '@/lib/useTableMaxHeight'

const tenantNames = ref({})
const tenantOptions = ref([])

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ pageNo: 1, pageSize: DEFAULT_PAGE_SIZE, keyword: '' })

// 两卡式列表页：表格只设最大高度，超高内部滚动
const { queryCardRef, footerRef, tableMaxHeight } = useTableMaxHeight()
const dialogVisible = ref(false)
const editing = ref(false)
const submitting = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const form = reactive({
  id: null, templateCode: '', templateName: '', certType: 'SERVER', algorithm: 'SM2',
  validityDays: 365, keyUsageList: [], extKeyUsage: '', policyOid: '', description: '',
  tenantScope: 'GLOBAL', tenantUscc: ''
})

function typeText(t) {
  return { SERVER: '服务端', CLIENT: '客户端', SUB_CA: '二级CA' }[t] || t
}

function onRowDetail(row) {
  detail.value = row
  detailVisible.value = true
}

function fillForm(row) {
  Object.assign(form, {
    id: row.id, templateCode: row.templateCode, templateName: row.templateName, certType: row.certType,
    algorithm: row.algorithm, validityDays: row.validityDays,
    keyUsageList: row.keyUsage ? row.keyUsage.split(',').map(s => s.trim()) : [],
    extKeyUsage: row.extKeyUsage || '', policyOid: row.policyOid || '', description: row.description || '',
    tenantScope: 'GLOBAL'
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

function handleSearch() {
  query.pageNo = 1
  load()
}

function handleReset() {
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

function openCreate() {
  editing.value = false
  fillForm({ id: null, templateCode: '', templateName: '', certType: 'SERVER', algorithm: 'SM2', validityDays: 365, keyUsage: '', extKeyUsage: '', policyOid: '', description: '' })
  // 缺省建全局模板；归属指定租户时按 tenantOptions 选择
  form.tenantScope = 'GLOBAL'
  form.tenantUscc = tenantOptions.value.find(o => o.status === 'ACTIVE')?.uscc || ''
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
  // 建模板归属口径显式传：全局模板 tenantUscc=''；指定租户=所选租户（租户账号由后端锁本域）
  if (!editing.value) {
    if (form.tenantScope === 'ORG' && !form.tenantUscc) {
      ElMessage.warning('请选择模板归属租户')
      return
    }
    payload.tenantUscc = form.tenantScope === 'ORG' ? form.tenantUscc : ''
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

onMounted(async () => {
  load()
  tenantNames.value = await tenantNameMap()
  tenantOptions.value = await listTenantsCached()
})
</script>
