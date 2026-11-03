package com.csssl.workflow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.mapper.FlowDefinitionMapper;
import com.csssl.mapper.FlowHisTaskMapper;
import com.csssl.mapper.FlowInstanceMapper;
import com.csssl.mapper.FlowNodeMapper;
import com.csssl.mapper.FlowSkipMapper;
import com.csssl.mapper.FlowTaskMapper;
import com.csssl.mapper.FlowUserMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.util.UserUtils;
import com.csssl.workflow.dto.StartRequest;
import com.csssl.workflow.dto.TaskVO;
import com.csssl.workflow.model.FlowDefinition;
import com.csssl.workflow.model.FlowHisTask;
import com.csssl.workflow.model.FlowInstance;
import com.csssl.workflow.model.FlowNode;
import com.csssl.workflow.model.FlowSkip;
import com.csssl.workflow.model.FlowTask;
import com.csssl.workflow.model.FlowUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
        if (req == null || req.getFlowCode() == null || req.getFlowCode().isBlank()) {
            throw new IllegalArgumentException("流程编码不能为空");
        }
        if (req.getBusinessId() == null || req.getBusinessId().isBlank()) {
            throw new IllegalArgumentException("业务单据ID不能为空");
        }
        LoginUser user = requireCurrentUser();
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
        createNodeTasks(ins, firstNode);
        return ins;
    }

    /**
     * 通过任务：CAS 抢占任务后再判断节点是否办结，办结则推进到下一节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public FlowInstance approve(Long taskId, String message) {
        LoginUser user = requireCurrentUser();
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
        if (!matchesUser(node.getPermissionFlag(), user)) {
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
        LoginUser user = requireCurrentUser();
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
        if (!matchesUser(node.getPermissionFlag(), user)) {
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
            if (node == null || !matchesUser(node.getPermissionFlag(), user)) {
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

    // ==================== 内部实现 ====================

    private void createNodeTasks(FlowInstance ins, FlowNode node) {
        List<String> candidates = resolveCandidates(node.getPermissionFlag());
        for (String username : candidates) {
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
            fu.setProcessedBy(username);
            fu.setAssociated(task.getId());
            fu.setCreateTime(LocalDateTime.now());
            fu.setUpdateTime(LocalDateTime.now());
            flowUserMapper.insert(fu);
        }
    }

    /**
     * 权限标识解析为候选账号：role:XXX 走角色，其余视为具体账号。
     */
    private List<String> resolveCandidates(String permissionFlag) {
        if (permissionFlag == null || permissionFlag.isBlank()) {
            throw new IllegalArgumentException("审批节点未配置权限标识，无法分派办理人");
        }
        List<String> result = new ArrayList<>();
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
                users.forEach(u -> result.add(u.getUsername()));
            } else {
                result.add(f);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("权限标识未匹配到任何办理人：" + permissionFlag);
        }
        return result.stream().distinct().collect(Collectors.toList());
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
            createNodeTasks(fresh, nextNode);
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

    private boolean matchesUser(String permissionFlag, LoginUser user) {
        if (permissionFlag == null || permissionFlag.isBlank()) {
            return false;
        }
        for (String raw : permissionFlag.split("@@")) {
            String f = raw.trim();
            if (f.startsWith("role:")) {
                if (f.substring("role:".length()).equalsIgnoreCase(user.getRoleCode())) {
                    return true;
                }
            } else if (f.equals(user.getUsername())) {
                return true;
            }
        }
        return false;
    }
}
