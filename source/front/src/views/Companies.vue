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
                placeholder="统一社会信用代码 / 名称 / 系统名"
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
        <el-table-column label="联系人" min-width="50">
          <template #default="{ row }">
            {{ row.contact || '-' }}
            <div v-if="row.contactPhone" class="cell-sub">{{ maskPhone(row.contactPhone) }}</div>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="320">
          <template #default="{ row }">
            <el-button
                v-for="a in visibleRowActions(row)"
                :key="a.label"
                :disabled="a.disabled"
                :type="a.type || 'primary'"
                link
                size="small"
                @click="a.run()"
            >{{ a.label }}
            </el-button>
            <el-dropdown v-if="moreRowActions(row).length" trigger="click" @command="cmd => cmd.run()">
              <el-button link size="small" type="primary">更多<el-icon class="el-icon--right"><ArrowDown/></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item v-for="a in moreRowActions(row)" :key="a.label" :command="a">{{ a.label }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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
        <el-form-item label="统一社会信用代码" prop="uscc">
          <el-input
              v-model="form.uscc"
              class="cell-mono"
              maxlength="18"
              placeholder="18 位，如 91430100MA4L5K2N3T"
              @input="onUsccInput"
          />
        </el-form-item>
        <el-form-item label="公司名称" prop="companyName">
          <el-input v-model="form.companyName" placeholder="如 长沙麓康健康管理集团"/>
        </el-form-item>
        <el-form-item label="系统名称" prop="subjectO">
          <el-input v-model="form.subjectO" maxlength="64" placeholder="如 HIS 生产核心（即证书主体 O，将创建该公司首个系统）"/>
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
      <el-alert v-else :closable="false" class="dialog-tip" show-icon
                title="提交后需公司审批员批准才执行。" type="info"/>
      <el-descriptions :column="2" border class="op-desc">
        <el-descriptions-item label="目标公司" :span="2">{{ opTarget?.companyName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="统一社会信用代码"><span class="cell-mono">{{ opTarget?.uscc || '-' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前状态">{{ opTarget ? statusText(opTarget.status) : '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-form class="op-form" label-width="90px">
        <el-form-item label="审批人" required>
          <el-select v-model="opAssignee" placeholder="从流程节点筛选出的审批人中选择" style="width: 100%">
            <el-option v-for="u in opAssigneeOptions" :key="u.id"
                       :label="`${u.realName}（${u.roleName || u.roleCode || '—'}）`" :value="u.id"/>
          </el-select>
        </el-form-item>
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

    <!-- 公司详情（行点击）：基本信息 + 公司下的系统/接入凭证 -->
    <el-dialog v-model="detailVisible" :title="`公司详情：${detail?.companyName || ''}`" width="900px">
      <el-tabs v-model="detailTab">
        <el-tab-pane label="基本信息" name="basic">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="统一社会信用代码">{{ detail?.uscc || '-' }}</el-descriptions-item>
            <el-descriptions-item label="公司名称">{{ detail?.companyName }}</el-descriptions-item>
            <el-descriptions-item label="首个系统（主体O）">{{ detail?.subjectO || '-' }}</el-descriptions-item>
            <el-descriptions-item label="算法">{{ detail?.algorithm || '-' }}</el-descriptions-item>
            <el-descriptions-item label="公司状态">{{ detail ? statusText(detail.status) : '' }}</el-descriptions-item>
            <el-descriptions-item label="根密钥状态">{{
                detail?.rootStatus ? rootKeyText(detail.rootStatus.rootKeyState) : '-'
              }}
            </el-descriptions-item>
            <el-descriptions-item label="根CA有效期" :span="2">{{
                detail ? validity(detail.rootStatus?.rootValidFrom, detail.rootStatus?.rootValidTo) : ''
              }}
            </el-descriptions-item>
            <el-descriptions-item label="二级CA有效期（SUB_CA）" :span="2">
              {{ detail ? validity(detail.rootStatus?.subCaValidFrom, detail.rootStatus?.subCaValidTo) : '' }}
            </el-descriptions-item>
            <el-descriptions-item label="联系人">{{ detail?.contact || '-' }}</el-descriptions-item>
            <el-descriptions-item label="联系方式">{{ maskPhone(detail?.contactPhone) }}</el-descriptions-item>
            <el-descriptions-item label="说明" :span="2">{{ detail?.description || '-' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ detail?.createdAt || '-' }}</el-descriptions-item>
            <el-descriptions-item label="密钥保管人" :span="2">
              <template v-if="detail?.holders?.length">
                <el-tag v-for="h in detail.holders" :key="h.shareIndex" size="small" style="margin-right: 6px">
                  第{{ h.shareIndex }}份 · {{ h.holderRealName }}
                </el-tag>
              </template>
              <span v-else>尚未指定</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <el-tab-pane :label="`公司下面的系统${detail?.systems?.length ? `（${detail.systems.length}）` : ''}`" name="systems">
          <el-table :data="detail?.systems || []" stripe size="small" style="width: 100%">
            <el-table-column label="系统名称（证书主体O）" min-width="200">
              <template #default="{ row }">
                <div>{{ row.systemName }}</div>
              </template>
            </el-table-column>
            <el-table-column class-name="cell-mono" label="接入ID（App ID）" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <template v-if="row.appId">
                  <span>{{ row.appId }}</span>
                  <el-button link size="small" type="primary" @click="onCopy(row.appId)">复制</el-button>
                </template>
                <span v-else class="cell-sub">未登记凭证</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.appId" :type="row.status === 1 ? 'success' : 'info'" size="small">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="密钥版本" width="90">
              <template #default="{ row }">{{ row.secretVersion ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="最近调用" width="160">
              <template #default="{ row }">{{ row.lastUsedAt || '—' }}</template>
            </el-table-column>
          </el-table>
          <div v-if="!detail?.systems?.length" class="empty-tip">该公司暂无系统</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 申请重建二级CA（从公司详情发起：已锁定公司，只填事由；5 名保管人 3-of-5 投票在待办里做） -->
    <el-dialog v-model="rebuildVisible" title="申请重建二级CA" width="560px">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
                title="重建将用根私钥重新签发本公司二级CA（SUB_CA），需 5 名密钥保管人中 3 人同意；同意时各保管人须在待办里提交本人份额。"/>
      <el-form label-width="90px">
        <el-form-item label="所属公司">
          <el-input :model-value="detail?.companyName" disabled/>
        </el-form-item>
        <el-form-item label="重建事由" required>
          <el-input v-model="rebuildReason" type="textarea" :rows="3" placeholder="说明为什么需要重建二级CA（如SUB_CA即将到期、怀疑私钥泄露等）"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rebuildVisible = false">取消</el-button>
        <el-button :loading="rebuilding" type="primary" @click="submitRebuild">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 重新指派密钥保管人（发起变更单：原 5 名保管人投票 >3 同意后执行，不直接绑定） -->
    <el-dialog v-model="reassignVisible" :close-on-click-modal="false" title="重新指派密钥保管人" width="680px">
      <el-alert :closable="false" class="dialog-tip" show-icon
                title="提交后由该公司原 5 名密钥保管人在「公司变更审批」页逐人投票，同意数超过 3（≥4）自动执行重绑；发起人本人不可作为新保管人。"
                type="warning"/>
      <el-descriptions :column="2" border class="op-desc">
        <el-descriptions-item label="目标公司" :span="2">{{ reassignTarget?.companyName || '-' }}</el-descriptions-item>
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
      <el-descriptions v-if="cred" :column="2" border class="cred-desc">
        <el-descriptions-item label="接入ID（App ID）" :span="2" label-width="150px">
          <span class="cell-mono">{{ cred.appId }}</span>
          <el-button link size="small" type="primary" @click="onCopy(cred.appId)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="接入密钥" :span="2">
          <span class="cell-mono">{{ cred.secret }}</span>
          <el-button link size="small" type="primary" @click="onCopy(cred.secret)">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="绑定公司">{{ cred.companyName || cred.companyUscc }}</el-descriptions-item>
        <el-descriptions-item label="密钥版本">{{ cred.secretVersion ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="所属系统">{{ cred.systemName || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" @click="credVisible = false">我已保存</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 系统接入（公司 → 系统 → 每系统一套独立共享凭证） -->
    <el-dialog v-model="clientsVisible" :close-on-click-modal="false"
               :title="`系统接入：${clientsTarget?.companyName || ''}`" width="940px">
      <div class="clients-toolbar">
        <el-button v-perm="'apiclient:manage'" type="primary" @click="openCreateSystem">新增系统</el-button>
        <span class="clients-tip">公司下每个系统一套独立共享密钥与专属二级 CA；系统名即证书主体 O，公司内不重名。</span>
      </div>
      <el-table v-loading="clientsLoading" :data="systemRows" stripe style="width: 100%">
        <el-table-column label="系统名称" min-width="180">
          <template #default="{ row }">
            <div>{{ row.systemName || row.clientName }}</div>
            <div v-if="row.systemName && row.clientName && row.clientName !== row.systemName" class="cell-sub">{{ row.clientName }}</div>
          </template>
        </el-table-column>
        <el-table-column class-name="cell-mono" label="接入ID（App ID）" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.appId">{{ row.appId }}</span>
            <span v-else class="cell-sub">未登记凭证</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.appId" :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="200">
          <template #default="{ row }">
            <el-button v-if="hasPerm('apiclient:manage') && !row.appId" link size="small" type="primary"
                       @click="openCreateCredential(row)">登记凭证</el-button>
            <template v-else-if="hasPerm('apiclient:manage')">
              <el-button link size="small" type="primary" @click="onRotateClient(row)">轮换密钥</el-button>
              <el-button link :type="row.status === 1 ? 'warning' : 'success'" size="small"
                         @click="onToggleClient(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!clientsLoading && systemRows.length === 0" description="该公司暂无系统，点击「新增系统」创建"/>
    </el-dialog>

    <!-- 新增系统：建系统同时登记它的接入凭证，secret 只展示一次 -->
    <el-dialog v-model="systemCreateVisible" :close-on-click-modal="false" title="新增系统" width="560px">
      <el-form :model="systemForm" label-width="120px">
        <el-form-item label="系统名称" required>
          <el-input v-model="systemForm.systemName" maxlength="64" placeholder="如 影像系统 PACS（即证书主体 O）"/>
          <div class="cell-sub">系统名进证书主体 O，签发后不可修改；同时作为该系统接入凭证的名称，最长 64 字符。</div>
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input v-model="systemForm.ipAllowlist" placeholder="可选，逗号分隔；留空表示不限制"/>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="systemForm.description" maxlength="200" placeholder="可选"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="systemCreateVisible = false">取消</el-button>
          <el-button :loading="systemSubmitting" type="primary" @click="onCreateSystem">创建并生成凭证</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 为尚无凭证的系统补登记接入凭证（凭证登记失败时用它续上） -->
    <el-dialog v-model="clientCreateVisible" :close-on-click-modal="false" title="登记接入凭证" width="520px">
      <el-form :model="clientForm" label-width="120px">
        <el-form-item label="系统名称" required>
          <el-input :model-value="clientForm.systemName" disabled/>
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input v-model="clientForm.ipAllowlist" placeholder="可选，逗号分隔；留空表示不限制"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="clientCreateVisible = false">取消</el-button>
          <el-button :loading="clientSubmitting" type="primary" @click="onCreateClient">生成凭证</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {ArrowDown, Plus, Refresh, RefreshRight, Search} from '@element-plus/icons-vue'
import {
  applySubCaRebuild,
  createApiClient,
  createCompany,
  createSystem,
  getCompany,
  listApiClients,
  listCompaniesCached,
  listFlowCandidates,
  listSimpleUsers,
  listSystems,
  pageCompanies,
  rotateApiClient,
  setApiClientStatus,
  submitCompanyOp
} from '@/api/system'
import {DEFAULT_PAGE_SIZE, PAGE_SIZES} from '@/lib/pagination'
import {useTableMaxHeight} from '@/lib/useTableMaxHeight'
import {hasPerm} from '@/lib/perm'
import {copyText} from '@/lib/clipboard'

const OP_TYPE_LABELS = {
  SUSPEND: '暂停公司',
  RESUME: '恢复公司',
  CLOSE: '关闭公司',
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
// 变更单提交时的审批人（流程首个审批节点候选，单选）
const opAssignee = ref(null)
const opAssigneeOptions = ref([])
const opTarget = ref(null)
const opReason = ref('')
const submitting = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const detailTab = ref('basic')

// ===== 申请重建二级CA（公司列表操作列发起） =====
const rebuildVisible = ref(false)
const rebuildReason = ref('')
const rebuilding = ref(false)
async function submitRebuild() {
  const reason = rebuildReason.value.trim()
  if (!reason) { ElMessage.warning('请填写重建事由'); return }
  rebuilding.value = true
  try {
    await applySubCaRebuild({companyUscc: detail.value.uscc, reason})
    ElMessage.success('已发起二级CA重建申请，已通知 5 名密钥保管人审批')
    rebuildVisible.value = false
  } finally { rebuilding.value = false }
}
const credVisible = ref(false)
const cred = ref(null)
const formRef = ref()

// ===== 系统接入（公司 → 系统 → 每系统一套独立共享凭证） =====
const clientsVisible = ref(false)
const clientsTarget = ref(null)
const clients = ref([])
const clientsLoading = ref(false)
const clientCreateVisible = ref(false)
const clientSubmitting = ref(false)
const clientForm = reactive({systemName: '', ipAllowlist: ''})

// ===== 公司下的系统（证书域单元）：系统名 = 主体 O，公司内唯一 =====
const systems = ref([])
const systemCreateVisible = ref(false)
const systemSubmitting = ref(false)
const systemForm = reactive({systemName: '', ipAllowlist: '', description: ''})

// 系统为行、凭证为列：一个系统多套凭证时各占一行；尚无凭证的系统也要出现在列表里
const systemRows = computed(() => {
  const list = clients.value.filter(c => c.companyUscc === clientsTarget.value?.uscc)
  const taken = new Set()
  const rows = []
  for (const s of systems.value) {
    const mine = list.filter(c => c.systemName === s.systemName && !taken.has(c.id))
    mine.forEach(c => taken.add(c.id))
    rows.push(...(mine.length ? mine : [{systemName: s.systemName}]))
  }
  // 存量凭证未归属任何系统（system_name 为空）时仍要展示，否则密钥既看不见也轮换不了
  rows.push(...list.filter(c => !taken.has(c.id)))
  return rows
})
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
  subjectO: [{required: true, message: '请输入系统名称（证书主体 O）', trigger: 'blur'}],
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

async function onRowDetail(row) {
  detail.value = row
  detailTab.value = 'basic'
  detailVisible.value = true
  // 拉完整详情（含 systems 列表 + rootStatus + holders），列表行字段不全
  try {
    const res = await getCompany(row.uscc)
    if (res?.data) detail.value = res.data
  } catch (e) { /* 详情接口失败时保留列表行数据 */ }
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

// 公司列表操作：全部是「提交变更单」，不直接执行；前 3 个短按钮平铺，长按钮收进「更多」（ACTION_VISIBLE=3）
function companyActions(row) {
  const acts = [{label: '详情', run: () => onRowDetail(row)}]
  // 系统接入（每系统一套共享凭证）：仅接入凭证管理员（apiclient:manage）
  if (hasPerm('apiclient:manage')) {
    acts.push({label: '系统接入', type: 'primary', run: () => openClients(row)})
  }
  if (!hasPerm('company:op:apply')) return acts
  if (row.status === 'ACTIVE') {
    acts.push({label: '申请暂停', type: 'warning', run: () => openOp('SUSPEND', row)})
  }
  if (row.status === 'SUSPENDED') {
    acts.push({label: '申请恢复', type: 'success', run: () => openOp('RESUME', row)})
  }
  if (row.status === 'CLOSED') {
    acts.push({label: '申请恢复', type: 'success', run: () => openOp('RESUME', row)})
  }
  // 长按钮统一收进「更多」
  if (row.status === 'ACTIVE' && hasPerm('ca:share:assign')) {
    acts.push({label: '重新指派保管人', type: 'primary', run: () => openReassignFlow(row)})
  }
  if (row.status === 'ACTIVE') {
    acts.push({label: '申请关闭', type: 'danger', run: () => openOp('CLOSE', row)})
  }
  // 重建二级CA：公司管理员/安全管理员发起，5 名保管人 3-of-5 投票在待办里做
  if (row.status === 'ACTIVE' && hasPerm('ca:subca:rebuild:apply')) {
    acts.push({label: '申请重建二级CA', type: 'warning', run: () => openRebuildFromRow(row)})
  }
  return acts
}

// 操作列按钮切分：最多平铺 3 个，超出收进「更多」下拉
const ACTION_VISIBLE = 3
function visibleRowActions(row) { return companyActions(row).slice(0, ACTION_VISIBLE) }
function moreRowActions(row) { return companyActions(row).slice(ACTION_VISIBLE) }

// 从列表行发起重建：先填到临时 row，复用详情里的重建弹窗
function openRebuildFromRow(row) {
  detail.value = row
  rebuildReason.value = ''
  rebuildVisible.value = true
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
    secretVersion: payload.secretVersion,
    systemName: payload.systemName
  }
  credVisible.value = true
}

// 系统接入：列表/新增/凭证登记/轮换/启停
async function openClients(row) {
  clientsTarget.value = row
  clientsVisible.value = true
  await Promise.all([loadClients(), loadSystems()])
}

async function loadClients() {
  clientsLoading.value = true
  try {
    const res = await listApiClients()
    clients.value = res.data || []
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    clientsLoading.value = false
  }
}

async function loadSystems() {
  if (!clientsTarget.value?.uscc) {
    systems.value = []
    return
  }
  try {
    const res = await listSystems(clientsTarget.value.uscc)
    systems.value = res.data || []
  } catch (e) {
    systems.value = []
  }
}

function openCreateSystem() {
  Object.assign(systemForm, {systemName: '', ipAllowlist: '', description: ''})
  systemCreateVisible.value = true
}

// 新增系统 = 建系统 + 登记该系统的接入凭证，一次做完只给一份一次性 secret
async function onCreateSystem() {
  const name = systemForm.systemName.trim()
  if (!name) {
    ElMessage.warning('请填写系统名称')
    return
  }
  systemSubmitting.value = true
  try {
    await createSystem({
      companyUscc: clientsTarget.value.uscc,
      systemName: name,
      description: systemForm.description.trim() || null
    })
    await loadSystems()
    const res = await createApiClient({
      companyUscc: clientsTarget.value.uscc,
      systemName: name,
      ipAllowlist: systemForm.ipAllowlist.trim() || null
    })
    systemCreateVisible.value = false
    ElMessage.success('系统已创建，请立即保存接入凭证')
    showCredential(res.data || {})
    await loadClients()
  } finally {
    systemSubmitting.value = false
  }
}

function openCreateCredential(row) {
  Object.assign(clientForm, {systemName: row.systemName, ipAllowlist: ''})
  clientCreateVisible.value = true
}

async function onCreateClient() {
  if (!clientForm.systemName) {
    ElMessage.warning('请先选择系统')
    return
  }
  clientSubmitting.value = true
  try {
    const res = await createApiClient({
      companyUscc: clientsTarget.value.uscc,
      systemName: clientForm.systemName,
      ipAllowlist: clientForm.ipAllowlist.trim() || null
    })
    clientCreateVisible.value = false
    ElMessage.success('接入凭证已生成，请立即保存')
    showCredential(res.data || {})
    await loadClients()
  } finally {
    clientSubmitting.value = false
  }
}

async function onRotateClient(row) {
  try {
    await ElMessageBox.confirm(`轮换「${row.systemName || row.clientName}」的接入密钥？旧密钥立即失效。`, '提示', {type: 'warning'})
  } catch {
    return
  }
  const res = await rotateApiClient(row.id)
  showCredential(res.data || {})
  await loadClients()
}

async function onToggleClient(row) {
  await setApiClientStatus(row.id, row.status !== 1)
  ElMessage.success(row.status === 1 ? '已停用' : '已启用')
  await loadClients()
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
    total.value = Number(res.data?.total) || 0
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
  opAssignee.value = null
  opAssigneeOptions.value = []
  listFlowCandidates('companyOpTicketFlow').then(res => {
    opAssigneeOptions.value = res.data || []
  }).catch(() => {
  })
  opVisible.value = true
}

async function onSubmitOp() {
  const reason = opReason.value.trim()
  if (!opAssignee.value) {
    ElMessage.warning('请选择审批人（候选范围由流程节点配置决定）')
    return
  }
  if (!reason) {
    ElMessage.warning('请填写变更事由')
    return
  }
  submitting.value = true
  try {
    await submitCompanyOp({
      opType: opType.value,
      uscc: opTarget.value.uscc,
      reason,
      assigneeUserId: opAssignee.value
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

.clients-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.clients-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
</style>
