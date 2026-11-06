package com.csssl.workflow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.mapper.*;
import com.csssl.util.UserUtils;
import com.csssl.workflow.dto.StartRequest;
import com.csssl.workflow.dto.TaskVO;
import com.csssl.workflow.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自研轻量工作流引擎。
 *
 * <p>只做线性审批流：开始 → 若干审批节点 → 结束，支持或签/会签/票签。
 * 并发安全靠「条件更新（CAS）」而不是行锁：</p>
 * <ul>
 *   <li>办理任务：{@code UPDATE flow_task SET flow_status='PASS' WHERE id=? AND flow_status='PENDING'}，
 *       影响行数为 0 即说明已被他人处理，直接报错，杜绝重复推进；</li>
 *   <li>推进节点：{@code UPDATE flow_instance SET node_code=新节点 WHERE id=? AND node_code=旧节点}，
 *       两个线程同时判定节点完成时只有一个能推进成功。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngineService {

    private static final String TASK_PENDING = "PENDING";
    private static final String TASK_PASS = "PASS";
    private static final String TASK_REJECT = "REJECT";
    private static final String TASK_CANCELED = "CANCELED";
    private static final String INS_RUNNING = "RUNNING";
    private static final String INS_APPROVED = "APPROVED";
    private static final String INS_REJECTED = "REJECTED";
    private static final String INS_CANCELED = "CANCELED";

    private final WorkflowDefService defService;
    private final FlowDefinitionMapper definitionMapper;
    private final FlowNodeMapper nodeMapper;
    private final FlowSkipMapper skipMapper;
    private final FlowInstanceMapper instanceMapper;
    private final FlowTaskMapper taskMapper;
    private final FlowHisTaskMapper hisTaskMapper;
    private final FlowUserMapper flowUserMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 启动流程：按已发布定义找开始节点的通过流转线，落到第一个审批节点并生成待办。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance start(StartRequest req) {
        return start(req, requireCurrentUser());
    }

    /**
     * 启动流程（发起人显式传入）：供无登录态通道（对外 API）建单时起流程用。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance start(StartRequest req, LoginUser user) {
        if (req == null || req.getFlowCode() == null || req.getFlowCode().isBlank()) {
            throw new IllegalArgumentException("流程编码不能为空");
        }
        if (req.getBusinessId() == null || req.getBusinessId().isBlank()) {
            throw new IllegalArgumentException("业务单据ID不能为空");
        }
        FlowDefinition def = defService.getPublished(req.getFlowCode());
        if (def == null) {
            throw new IllegalArgumentException("流程未发布或不存在：" + req.getFlowCode());
        }
        List<FlowNode> nodes = nodeMapper.selectList(new QueryWrapper<FlowNode>()
                .eq("definition_id", def.getId()));
        List<FlowSkip> skips = skipMapper.selectList(new QueryWrapper<FlowSkip>()
                .eq("definition_id", def.getId()));

        FlowNode startNode = nodes.stream()
                .filter(n -> n.getNodeType() != null && n.getNodeType() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("流程缺少开始节点"));

        FlowSkip firstSkip = skips.stream()
                .filter(s -> startNode.getNodeCode().equals(s.getNowNodeCode()) && "PASS".equals(s.getSkipType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("开始节点缺少通过流转线"));
        FlowNode firstNode = nodes.stream()
                .filter(n -> firstSkip.getNextNodeCode().equals(n.getNodeCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("开始节点指向的节点不存在"));

        FlowInstance ins = new FlowInstance();
        ins.setDefinitionId(def.getId());
        ins.setFlowName(def.getFlowName());
        ins.setBusinessId(req.getBusinessId());
        ins.setStartedByUserId(user.getUserId());
        ins.setStartedByName(user.getRealName());
        ins.setNodeCode(firstNode.getNodeCode());
        ins.setNodeName(firstNode.getNodeName());
        ins.setNodeType(firstNode.getNodeType());
        ins.setFlowStatus(INS_RUNNING);
        ins.setActivityStatus(1);
        ins.setDefJson(def.getModelValue());
        ins.setCreateTime(LocalDateTime.now());
        ins.setUpdateTime(LocalDateTime.now());
        instanceMapper.insert(ins);

        recordHistory(ins, null, startNode, user, TASK_PASS, "发起流程");
        createNodeTasks(ins, firstNode, true, req.getAssigneeUserId());
        return ins;
    }

    /**
     * 通过任务：CAS 抢占任务后再判断节点是否办结，办结则推进到下一节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance approve(Long taskId, String message) {
        return doApprove(taskId, requireCurrentUser(), message, true);
    }

    /**
     * 通过任务内部实现；checkCandidate=false 时跳过节点办理人校验，
     * 仅供业务侧已完成自身校验（如 SUB_CA 保管人投票）后记账用。
     */
    private FlowInstance doApprove(Long taskId, LoginUser user, String message, boolean checkCandidate) {
        FlowTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("待办任务不存在（id=" + taskId + "）");
        }
        if (!TASK_PENDING.equals(task.getFlowStatus())) {
            throw new IllegalArgumentException("该待办已被处理，请刷新后重试");
        }
        FlowInstance ins = instanceMapper.selectById(task.getInstanceId());
        if (ins == null) {
            throw new IllegalArgumentException("流程实例不存在");
        }
        FlowNode node = nodeMapper.selectOne(new QueryWrapper<FlowNode>()
                .eq("definition_id", ins.getDefinitionId())
                .eq("node_code", task.getNodeCode())
                .last("limit 1"));
        if (node == null) {
            throw new IllegalArgumentException("节点定义不存在（nodeCode=" + task.getNodeCode() + "）");
        }
        if (checkCandidate && !isTaskAssignee(task, user)) {
            throw new IllegalArgumentException("您不在该节点的办理人范围内，无法处理此待办");
        }
        int affected = taskMapper.update(null, new UpdateWrapper<FlowTask>()
                .eq("id", taskId)
                .eq("flow_status", TASK_PENDING)
                .set("flow_status", TASK_PASS)
                .set("update_time", LocalDateTime.now()));
        if (affected == 0) {
            throw new IllegalArgumentException("该待办已被其他人处理，请刷新后重试");
        }

        recordHistory(ins, taskId, node, user, TASK_PASS, message);

        if (isNodeCompleted(ins, task)) {
            advance(ins, node);
        }
        return instanceMapper.selectById(ins.getId());
    }

    /**
     * 驳回任务：当前节点直接终止，实例置为驳回。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance reject(Long taskId, String message) {
        return doReject(taskId, requireCurrentUser(), message, true);
    }

    /**
     * 驳回任务内部实现；checkCandidate 语义同 {@link #doApprove}。
     */
    private FlowInstance doReject(Long taskId, LoginUser user, String message, boolean checkCandidate) {
        FlowTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("待办任务不存在（id=" + taskId + "）");
        }
        FlowInstance ins = instanceMapper.selectById(task.getInstanceId());
        if (ins == null) {
            throw new IllegalArgumentException("流程实例不存在");
        }
        FlowNode node = nodeMapper.selectOne(new QueryWrapper<FlowNode>()
                .eq("definition_id", ins.getDefinitionId())
                .eq("node_code", task.getNodeCode())
                .last("limit 1"));
        if (node == null) {
            throw new IllegalArgumentException("节点定义不存在（nodeCode=" + task.getNodeCode() + "）");
        }
        if (checkCandidate && !isTaskAssignee(task, user)) {
            throw new IllegalArgumentException("您不在该节点的办理人范围内，无法处理此待办");
        }
        int affected = taskMapper.update(null, new UpdateWrapper<FlowTask>()
                .eq("id", taskId)
                .eq("flow_status", TASK_PENDING)
                .set("flow_status", TASK_REJECT)
                .set("update_time", LocalDateTime.now()));
        if (affected == 0) {
            throw new IllegalArgumentException("该待办已被其他人处理，请刷新后重试");
        }
        cancelPendingTasks(task.getInstanceId(), task.getNodeCode());

        instanceMapper.update(null, new UpdateWrapper<FlowInstance>()
                .eq("id", ins.getId())
                .eq("flow_status", INS_RUNNING)
                .set("flow_status", INS_REJECTED)
                .set("update_time", LocalDateTime.now()));
        recordHistory(ins, taskId, node, user, TASK_REJECT, message);
        return instanceMapper.selectById(ins.getId());
    }

    /**
     * 撤回：仅发起人可撤，运行中的实例置为取消。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance withdraw(Long instanceId) {
        LoginUser user = requireCurrentUser();
        FlowInstance ins = instanceMapper.selectById(instanceId);
        if (ins == null) {
            throw new IllegalArgumentException("流程实例不存在（id=" + instanceId + "）");
        }
        if (ins.getStartedByUserId() == null || !ins.getStartedByUserId().equals(user.getUserId())) {
            throw new IllegalArgumentException("只有发起人可以撤回该流程");
        }
        int affected = instanceMapper.update(null, new UpdateWrapper<FlowInstance>()
                .eq("id", instanceId)
                .eq("flow_status", INS_RUNNING)
                .set("flow_status", INS_CANCELED)
                .set("update_time", LocalDateTime.now()));
        if (affected == 0) {
            throw new IllegalArgumentException("流程已结束，无法撤回");
        }
        cancelPendingTasks(instanceId, ins.getNodeCode());
        return instanceMapper.selectById(instanceId);
    }

    /**
     * 我的待办：PENDING 任务中，节点权限标识命中当前用户角色或账号的任务。
     */
    public List<TaskVO> myPendingTasks() {
        LoginUser user = requireCurrentUser();
        List<FlowTask> pending = taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("flow_status", TASK_PENDING)
                .orderByDesc("create_time"));
        if (pending.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> defIds = pending.stream().map(FlowTask::getDefinitionId).distinct().collect(Collectors.toList());
        List<FlowNode> nodes = nodeMapper.selectList(new QueryWrapper<FlowNode>().in("definition_id", defIds));

        List<TaskVO> result = new ArrayList<>();
        for (FlowTask t : pending) {
            FlowNode node = nodes.stream()
                    .filter(n -> n.getDefinitionId().equals(t.getDefinitionId())
                            && n.getNodeCode().equals(t.getNodeCode()))
                    .findFirst().orElse(null);
            if (node == null || !isTaskAssignee(t, user)) {
                continue;
            }
            TaskVO vo = new TaskVO();
            vo.setId(t.getId());
            vo.setInstanceId(t.getInstanceId());
            vo.setFlowName(t.getFlowName());
            vo.setBusinessId(t.getBusinessId());
            vo.setNodeCode(t.getNodeCode());
            vo.setNodeName(t.getNodeName());
            vo.setPermissionFlag(node.getPermissionFlag());
            vo.setFlowStatus(t.getFlowStatus());
            vo.setCreateTime(t.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    public List<FlowHisTask> history(String businessId) {
        return hisTaskMapper.selectList(new QueryWrapper<FlowHisTask>()
                .eq("business_id", businessId)
                .orderByAsc("create_time"));
    }

    // ==================== 业务流接入（按业务单号驱动） ====================

    /**
     * 业务提交时发起流程。流程未发布直接报错——不允许静默绕过设计好的流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance startForBusiness(String flowCode, String businessId) {
        return startForBusiness(flowCode, businessId, null);
    }

    /**
     * 业务提交时发起流程，并把首个审批节点指派给提交人当场选定的那个人（单选）。
     * assigneeUserId 为空时按节点权限标识自动解析候选人。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance startForBusiness(String flowCode, String businessId, Long assigneeUserId) {
        StartRequest req = new StartRequest();
        req.setFlowCode(flowCode);
        req.setBusinessId(businessId);
        req.setAssigneeUserId(assigneeUserId);
        return start(req);
    }

    /**
     * 无登录态通道（对外 API）建单时发起流程：发起人不是自然人，
     * 按「人员身份口径」发起人 id 留空、姓名写 API 客户端名称。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance startForBusiness(String flowCode, String businessId, Long assigneeUserId,
                                         Long starterUserId, String starterName) {
        StartRequest req = new StartRequest();
        req.setFlowCode(flowCode);
        req.setBusinessId(businessId);
        req.setAssigneeUserId(assigneeUserId);
        return start(req, new LoginUser(starterUserId, starterName, null, false, null));
    }

    /**
     * 首个审批节点的候选人（提交表单的「审批人」下拉数据源）：
     * 由节点权限标识（role:XXX / user:xxx）解析，口径与流程启动时完全一致。
     */
    public List<SysUser> firstNodeCandidates(String flowCode) {
        FlowDefinition def = defService.getPublished(flowCode);
        if (def == null) {
            throw new IllegalArgumentException("流程未发布或不存在：" + flowCode);
        }
        FlowNode firstNode = firstApprovalNode(def);
        return resolveCandidates(firstNode.getPermissionFlag());
    }

    /** 该业务单是否已有流程实例（历史存量单没有实例，业务侧回落到旧口径） */
    public boolean hasInstance(String businessId) {
        return instanceMapper.selectCount(new QueryWrapper<FlowInstance>()
                .eq("business_id", businessId)) > 0;
    }

    /** 当前登录人能否处理该业务单的当前环节（节点办理人配置为唯一口径） */
    public boolean canHandle(String businessId, LoginUser user) {
        return findTaskForUser(businessId, user) != null;
    }

    /**
     * 业务审批：按业务单号找到当前环节派给当前登录人的待办并通过。
     * 返回实例最新状态：APPROVED=流程走完，RUNNING=还有后续节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance approveByBusiness(String businessId, LoginUser user, String message) {
        FlowTask task = findTaskForUser(businessId, user);
        if (task == null) {
            throw new IllegalArgumentException("当前环节没有派给您的待办，您不在该节点的办理人范围内");
        }
        return doApprove(task.getId(), user, message, true);
    }

    /**
     * 业务驳回：按业务单号找到当前登录人的待办并驳回，流程终止。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance rejectByBusiness(String businessId, LoginUser user, String message) {
        FlowTask task = findTaskForUser(businessId, user);
        if (task == null) {
            throw new IllegalArgumentException("当前环节没有派给您的待办，您不在该节点的办理人范围内");
        }
        return doReject(task.getId(), user, message, true);
    }

    /**
     * 业务侧已完成自身校验（如 SUB_CA 保管人投票）后记账用：通过本人名下待办，
     * 无本人任务时取该实例任一待办（票签累计）。流程已结束或无待办时返回 null。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance passOnBehalf(String businessId, LoginUser user, String message) {
        FlowTask task = pickTask(businessId, user.getUserId());
        if (task == null) {
            return null;
        }
        return doApprove(task.getId(), user, message, false);
    }

    /** 驳回记账（业务侧已校验），语义同 {@link #passOnBehalf} */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance rejectOnBehalf(String businessId, LoginUser user, String message) {
        FlowTask task = pickTask(businessId, user.getUserId());
        if (task == null) {
            return null;
        }
        return doReject(task.getId(), user, message, false);
    }

    /**
     * 业务执行动作完成（签发 / 执行变更 / 重建）后收尾：流程置为通过，剩余待办作废。
     * 无运行中实例（历史单）时返回 null。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance finish(String businessId, LoginUser actor, String message) {
        FlowInstance ins = latestRunningInstance(businessId);
        if (ins == null) {
            return null;
        }
        int affected = instanceMapper.update(null, new UpdateWrapper<FlowInstance>()
                .eq("id", ins.getId())
                .eq("flow_status", INS_RUNNING)
                .set("flow_status", INS_APPROVED)
                .set("update_time", LocalDateTime.now()));
        if (affected == 0) {
            return instanceMapper.selectById(ins.getId());
        }
        cancelPendingTasks(ins.getId(), ins.getNodeCode());
        recordHistory(ins, null, null, actor, TASK_PASS,
                message == null ? "业务执行完成，流程结束" : message);
        return instanceMapper.selectById(ins.getId());
    }

    /**
     * 业务撤销时同步撤回流程：仅发起人本人可撤；无运行中实例或非发起人时静默忽略，
     * 由业务侧自己给出「仅发起人可撤」的报错。
     */
    @Transactional(rollbackFor = Exception.class)
    public void withdrawByBusiness(String businessId, LoginUser user) {
        FlowInstance ins = latestRunningInstance(businessId);
        if (ins == null) {
            return;
        }
        if (ins.getStartedByUserId() == null || user == null
                || !ins.getStartedByUserId().equals(user.getUserId())) {
            return;
        }
        withdraw(ins.getId());
    }

    /** 当前环节第一个待办的办理人（业务单冗余 assignee 字段从这里回填，仅用于展示） */
    public SysUser firstPendingAssignee(String businessId) {
        List<SysUser> all = pendingAssignees(businessId);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 当前环节全部待办办理人（去重，供业务侧逐个推送待办通知） */
    public List<SysUser> pendingAssignees(String businessId) {
        List<SysUser> result = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (FlowTask t : pendingTasks(businessId)) {
            List<FlowUser> fus = flowUserMapper.selectList(new QueryWrapper<FlowUser>()
                    .eq("associated", t.getId()));
            for (FlowUser fu : fus) {
                SysUser u = null;
                try {
                    u = sysUserMapper.selectById(Long.valueOf(fu.getProcessedBy()));
                } catch (NumberFormatException ignored) {
                    // 历史数据可能存账号，忽略
                }
                if (u != null && seen.add(u.getId())) {
                    result.add(u);
                }
            }
        }
        return result;
    }

    // ==================== 内部实现 ====================

    private List<FlowTask> pendingTasks(String businessId) {
        return taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("business_id", businessId)
                .eq("flow_status", TASK_PENDING)
                .orderByAsc("id"));
    }

    private FlowInstance latestRunningInstance(String businessId) {
        return instanceMapper.selectOne(new QueryWrapper<FlowInstance>()
                .eq("business_id", businessId)
                .eq("flow_status", INS_RUNNING)
                .orderByDesc("id")
                .last("limit 1"));
    }

    /** 已发布定义的第一个审批节点（开始节点 PASS 流转线指向的节点） */
    private FlowNode firstApprovalNode(FlowDefinition def) {
        List<FlowNode> nodes = nodeMapper.selectList(new QueryWrapper<FlowNode>()
                .eq("definition_id", def.getId()));
        List<FlowSkip> skips = skipMapper.selectList(new QueryWrapper<FlowSkip>()
                .eq("definition_id", def.getId()));
        FlowNode startNode = nodes.stream()
                .filter(n -> n.getNodeType() != null && n.getNodeType() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("流程缺少开始节点"));
        FlowSkip firstSkip = skips.stream()
                .filter(s -> startNode.getNodeCode().equals(s.getNowNodeCode()) && "PASS".equals(s.getSkipType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("开始节点缺少通过流转线"));
        return nodes.stream()
                .filter(n -> firstSkip.getNextNodeCode().equals(n.getNodeCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("开始节点指向的节点不存在"));
    }

    /**
     * 当前环节派给该登录人的待办：以 flow_user 任务分配明细为准（会签/票签节点每人一条任务，
     * 只能处理自己那条）；只有历史任务没有分配明细时才回落到节点权限标识匹配。
     */
    private FlowTask findTaskForUser(String businessId, LoginUser user) {
        List<FlowTask> tasks = pendingTasks(businessId);
        boolean anyAssigned = false;
        for (FlowTask t : tasks) {
            List<FlowUser> fus = flowUserMapper.selectList(new QueryWrapper<FlowUser>()
                    .eq("associated", t.getId()));
            if (!fus.isEmpty()) {
                anyAssigned = true;
            }
            for (FlowUser fu : fus) {
                if (String.valueOf(user.getUserId()).equals(fu.getProcessedBy())) {
                    return t;
                }
            }
        }
        if (anyAssigned) {
            return null;
        }
        for (FlowTask t : tasks) {
            FlowNode node = nodeMapper.selectOne(new QueryWrapper<FlowNode>()
                    .eq("definition_id", t.getDefinitionId())
                    .eq("node_code", t.getNodeCode())
                    .last("limit 1"));
            if (node != null && matchesUser(node.getPermissionFlag(), user)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 待办是否属于该登录人：有分配明细（flow_user）时严格按明细，无明细的历史任务按节点权限兜底。
     */
    private boolean isTaskAssignee(FlowTask task, LoginUser user) {
        List<FlowUser> fus = flowUserMapper.selectList(new QueryWrapper<FlowUser>()
                .eq("associated", task.getId()));
        if (!fus.isEmpty()) {
            for (FlowUser fu : fus) {
                if (String.valueOf(user.getUserId()).equals(fu.getProcessedBy())) {
                    return true;
                }
            }
            return false;
        }
        FlowNode node = nodeMapper.selectOne(new QueryWrapper<FlowNode>()
                .eq("definition_id", task.getDefinitionId())
                .eq("node_code", task.getNodeCode())
                .last("limit 1"));
        return node != null && matchesUser(node.getPermissionFlag(), user);
    }

    private FlowTask pickTask(String businessId, Long userId) {
        List<FlowTask> tasks = pendingTasks(businessId);
        for (FlowTask t : tasks) {
            List<FlowUser> fus = flowUserMapper.selectList(new QueryWrapper<FlowUser>()
                    .eq("associated", t.getId()));
            for (FlowUser fu : fus) {
                if (String.valueOf(userId).equals(fu.getProcessedBy())) {
                    return t;
                }
            }
        }
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    /**
     * @param strict 首次进节点（发起流程）时必须能解析出办理人，配错要立刻暴露；
     *               流转进入后续节点时容忍为空（仅记日志），避免因某个执行节点暂时没人而卡死业务审批。
     */
    private void createNodeTasks(FlowInstance ins, FlowNode node, boolean strict) {
        createNodeTasks(ins, node, strict, null);
    }

    /**
     * @param strict         首次进节点（发起流程）时必须能解析出办理人，配错要立刻暴露；
     *                       流转进入后续节点时容忍为空（仅记日志），避免因某个执行节点暂时没人而卡死业务审批。
     * @param assigneeUserId 提交时人工选定的办理人（单选）：只给他一人建待办；
     *                       必须落在该节点权限标识筛选出的候选人范围内，否则报错。
     */
    private void createNodeTasks(FlowInstance ins, FlowNode node, boolean strict, Long assigneeUserId) {
        List<SysUser> candidates;
        if (assigneeUserId != null) {
            List<SysUser> allowed = resolveCandidates(node.getPermissionFlag());
            SysUser chosen = allowed.stream()
                    .filter(u -> assigneeUserId.equals(u.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "所选审批人不在节点「" + node.getNodeName() + "」的办理人范围内，请重新选择"));
            candidates = List.of(chosen);
        } else {
            try {
                candidates = resolveCandidates(node.getPermissionFlag());
            } catch (IllegalArgumentException e) {
                if (strict) {
                    throw e;
                }
                log.warn("节点 {}「{}」未匹配到办理人，流程实例 {} 停在该节点：{}",
                        node.getNodeCode(), node.getNodeName(), ins.getId(), e.getMessage());
                return;
            }
        }
        for (SysUser candidate : candidates) {
            FlowTask task = new FlowTask();
            task.setDefinitionId(ins.getDefinitionId());
            task.setInstanceId(ins.getId());
            task.setFlowName(ins.getFlowName());
            task.setBusinessId(ins.getBusinessId());
            task.setNodeCode(node.getNodeCode());
            task.setNodeName(node.getNodeName());
            task.setNodeType(node.getNodeType());
            task.setFlowStatus(TASK_PENDING);
            task.setFormCustom("N");
            task.setCreateTime(LocalDateTime.now());
            task.setUpdateTime(LocalDateTime.now());
            taskMapper.insert(task);

            FlowUser fu = new FlowUser();
            fu.setType("1");
            // LoginUser 不暴露账号，办理人一律按 user_id 记账
            fu.setProcessedBy(String.valueOf(candidate.getId()));
            fu.setAssociated(task.getId());
            fu.setCreateTime(LocalDateTime.now());
            fu.setUpdateTime(LocalDateTime.now());
            flowUserMapper.insert(fu);
        }
    }

    /**
     * 权限标识解析为候选人：role:XXX 走角色，user:xxx 走具体账号。返回用户实体，便于按 user_id 记账。
     */
    private List<SysUser> resolveCandidates(String permissionFlag) {
        if (permissionFlag == null || permissionFlag.isBlank()) {
            throw new IllegalArgumentException("审批节点未配置权限标识，无法分派办理人");
        }
        List<SysUser> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (String flag : permissionFlag.split("@@")) {
            String f = flag.trim();
            if (f.isEmpty()) {
                continue;
            }
            if (f.startsWith("role:")) {
                String roleCode = f.substring("role:".length());
                List<SysUser> users = sysUserMapper.selectList(new QueryWrapper<SysUser>()
                        .eq("role_code", roleCode)
                        .eq("status", 1));
                users.forEach(u -> {
                    if (seen.add(u.getId())) {
                        result.add(u);
                    }
                });
            } else {
                // 固定人员：user:xxx；兼容历史的裸账号写法
                String username = f.startsWith("user:") ? f.substring("user:".length()) : f;
                SysUser target = sysUserMapper.selectOne(new QueryWrapper<SysUser>()
                        .eq("username", username)
                        .last("limit 1"));
                if (target == null || (target.getStatus() != null && target.getStatus() != 1)) {
                    throw new IllegalArgumentException("指定办理人不存在或已停用：" + username);
                }
                if (seen.add(target.getId())) {
                    result.add(target);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("权限标识未匹配到任何办理人：" + permissionFlag);
        }
        return result;
    }

    /**
     * 节点办结判定：或签 1 票即过；会签需全部通过；票签按比例取整上限。
     */
    private boolean isNodeCompleted(FlowInstance ins, FlowTask task) {
        int total = taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("instance_id", ins.getId())
                .eq("node_code", task.getNodeCode())).size();
        int passed = taskMapper.selectList(new QueryWrapper<FlowTask>()
                .eq("instance_id", ins.getId())
                .eq("node_code", task.getNodeCode())
                .eq("flow_status", TASK_PASS)).size();

        FlowNode node = nodeMapper.selectOne(new QueryWrapper<FlowNode>()
                .eq("definition_id", ins.getDefinitionId())
                .eq("node_code", task.getNodeCode())
                .last("limit 1"));
        double ratio = parseRatio(node == null ? null : node.getNodeRatio());
        if (ratio <= 0) {
            return true;
        }
        if (total <= 0) {
            return true;
        }
        int required = ratio >= 100 ? total : (int) Math.ceil(total * ratio / 100.0);
        return passed >= required;
    }

    private double parseRatio(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return 0D;
        }
        try {
            return Double.parseDouble(ratio);
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    /**
     * 推进到下一节点；用实例当前节点做 CAS，避免并发重复推进。
     */
    private void advance(FlowInstance ins, FlowNode currentNode) {
        FlowSkip nextSkip = skipMapper.selectList(new QueryWrapper<FlowSkip>()
                        .eq("definition_id", ins.getDefinitionId())
                        .eq("now_node_code", currentNode.getNodeCode())
                        .eq("skip_type", "PASS"))
                .stream().findFirst().orElse(null);
        if (nextSkip == null) {
            throw new IllegalArgumentException("节点「" + currentNode.getNodeName() + "」缺少通过流转线");
        }
        FlowNode nextNode = nodeMapper.selectOne(new QueryWrapper<FlowNode>()
                .eq("definition_id", ins.getDefinitionId())
                .eq("node_code", nextSkip.getNextNodeCode())
                .last("limit 1"));
        if (nextNode == null) {
            throw new IllegalArgumentException("下一节点不存在：" + nextSkip.getNextNodeCode());
        }

        boolean isEnd = nextNode.getNodeType() != null && nextNode.getNodeType() == 2;
        UpdateWrapper<FlowInstance> uw = new UpdateWrapper<FlowInstance>()
                .eq("id", ins.getId())
                .eq("node_code", currentNode.getNodeCode())
                .set("node_code", nextNode.getNodeCode())
                .set("node_name", nextNode.getNodeName())
                .set("node_type", nextNode.getNodeType())
                .set("update_time", LocalDateTime.now());
        if (isEnd) {
            uw.set("flow_status", INS_APPROVED);
        }
        int affected = instanceMapper.update(null, uw);
        if (affected == 0) {
            // 已被其他线程推进，直接返回
            return;
        }
        // 或签场景下同节点多余的待办作废
        cancelPendingTasks(ins.getId(), currentNode.getNodeCode());
        if (!isEnd) {
            FlowInstance fresh = instanceMapper.selectById(ins.getId());
            createNodeTasks(fresh, nextNode, false);
        }
    }

    private void cancelPendingTasks(Long instanceId, String nodeCode) {
        taskMapper.update(null, new UpdateWrapper<FlowTask>()
                .eq("instance_id", instanceId)
                .eq("node_code", nodeCode)
                .eq("flow_status", TASK_PENDING)
                .set("flow_status", TASK_CANCELED)
                .set("update_time", LocalDateTime.now()));
    }

    private void recordHistory(FlowInstance ins, Long taskId, FlowNode node, LoginUser user,
                               String skipType, String message) {
        FlowHisTask his = new FlowHisTask();
        his.setDefinitionId(ins.getDefinitionId());
        his.setFlowName(ins.getFlowName());
        his.setInstanceId(ins.getId());
        his.setTaskId(taskId);
        his.setBusinessId(ins.getBusinessId());
        his.setNodeCode(node == null ? ins.getNodeCode() : node.getNodeCode());
        his.setNodeName(node == null ? ins.getNodeName() : node.getNodeName());
        his.setNodeType(node == null ? ins.getNodeType() : node.getNodeType());
        his.setApprover(user.getUsername());
        his.setCollaborator(user.getRealName());
        his.setSkipType(skipType);
        his.setFlowStatus(skipType);
        his.setMessage(message);
        his.setCreateTime(LocalDateTime.now());
        his.setUpdateTime(LocalDateTime.now());
        hisTaskMapper.insert(his);
    }

    private LoginUser requireCurrentUser() {
        LoginUser user = UserUtils.getLoginUser();
        if (user == null) {
            throw new IllegalStateException("当前用户信息不存在");
        }
        return user;
    }

    /**
     * 与 {@link #resolveCandidates} 同一口径：role:角色编码 命中角色，user:用户名 命中账号，
     * 兼容历史裸账号写法。两者必须对称，否则会「待办派得出去、本人却看不到/批不了」。
     */
    private boolean matchesUser(String permissionFlag, LoginUser user) {
        if (permissionFlag == null || permissionFlag.isBlank()) {
            return false;
        }
        String myRole = user.getRoleCode();
        for (String raw : permissionFlag.split("@@")) {
            String f = raw.trim();
            if (f.isEmpty()) {
                continue;
            }
            if (f.startsWith("role:")) {
                if (myRole != null && f.substring("role:".length()).equalsIgnoreCase(myRole)) {
                    return true;
                }
            } else {
                // 固定人员配置存的是账号，但 LoginUser 不暴露账号，只能回查 id 比对
                String username = f.startsWith("user:") ? f.substring("user:".length()) : f;
                SysUser target = sysUserMapper.selectOne(new QueryWrapper<SysUser>()
                        .eq("username", username).last("limit 1"));
                if (target != null && target.getId().equals(user.getUserId())) {
                    return true;
                }
            }
        }
        return false;
    }
}
