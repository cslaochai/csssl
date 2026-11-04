package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.WorkflowDiagramDTO;
import com.csssl.service.WorkflowTodoService;
import com.csssl.util.UserUtils;
import com.csssl.vo.WorkflowDiagramVO;
import com.csssl.vo.WorkflowStepStateVO;
import com.csssl.vo.WorkflowTodoBoardVO;
import com.csssl.enums.AssignStrategyEnum;
import com.csssl.workflow.WorkflowDefinition;
import com.csssl.workflow.WorkflowRegistry;
import com.csssl.workflow.WorkflowStep;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流定义与流程图（只读）：前端按定义生成流程图、推导当前步/下一处理人。
 */
@RestController
@RequestMapping("/admin/workflows")
@PreAuthorize("isAuthenticated()")
public class WorkflowController {

    private final WorkflowRegistry registry;
    private final WorkflowTodoService todoService;

    public WorkflowController(WorkflowRegistry registry, WorkflowTodoService todoService) {
        this.registry = registry;
        this.todoService = todoService;
    }

    /**
     * 全部工作流定义（用于总览页）
     */
    @PostMapping("/list")
    public Result<List<WorkflowDefinition>> list() {
        return Result.success("查询成功", registry.all());
    }

    /**
     * 按业务类型 + 当前状态推导流程图（含当前步高亮、当前待处理人提示）。
     *
     * <p>assigneeRealName 用于「当前停留步骤」为 {@link AssignStrategyEnum#SINGLE_LEAST_BACKLOG} 时，
     * 把抽象的角色标签换成具体人（如「张三（审核员 / RA管理员）」）——
     * 这正是"提交后看不到下一个审批人是谁"的答案：卡片上停留的那一步，就是现在该他动手。</p>
     */
    @PostMapping("/diagram")
    public Result<WorkflowDiagramVO> diagram(@Valid @RequestBody WorkflowDiagramDTO body) {
        if (body.getBizType() == null || body.getBizType().isBlank()) {
            throw new IllegalArgumentException("参数 bizType 不能为空");
        }
        if (body.getStatus() == null || body.getStatus().isBlank()) {
            throw new IllegalArgumentException("参数 status 不能为空");
        }
        String bizType = body.getBizType();
        String status = body.getStatus();
        String assigneeRealName = body.getAssigneeRealName();

        WorkflowDefinition def = registry.get(bizType);
        if (def == null) {
            throw new IllegalArgumentException("未知业务类型：" + bizType);
        }
        WorkflowStep current = def.currentStep(status);
        String currentCode = current == null ? null : current.getCode();
        int currentIdx = def.indexOfStep(currentCode);

        boolean rejected = "REJECTED".equalsIgnoreCase(status);
        boolean cancelled = "CANCELLED".equalsIgnoreCase(status);

        List<WorkflowStepStateVO> steps = new ArrayList<>();
        for (WorkflowStep s : def.getSteps()) {
            String state;
            int si = def.indexOfStep(s.getCode());

            if (rejected || cancelled) {
                // 驳回/撤销：决策节点本身算 DONE（决策已做出），它之前的节点也 DONE，
                // 它之后但终态之前的节点 SKIPPED（不会再走到了），终态步用状态本义。
                if (s.isTerminal()) {
                    state = rejected ? "REJECTED" : "CANCELLED";
                } else if (currentCode != null && s.getCode().equals(currentCode)) {
                    state = "DONE";
                } else if (si >= 0 && currentIdx >= 0 && si < currentIdx) {
                    state = "DONE";
                } else {
                    state = "SKIPPED";
                }
            } else if (currentCode == null) {
                state = "PENDING";
            } else if (s.getCode().equals(currentCode)) {
                state = "CURRENT";
            } else if (s.isTerminal()) {
                state = "DONE";
            } else {
                state = si >= 0 && si < currentIdx ? "DONE" : "PENDING";
            }

            steps.add(new WorkflowStepStateVO(s.getCode(), s.getName(), s.getActorLabel(),
                    s.getAssignStrategy().name(), s.isTerminal(), state));
        }

        // 驳回/撤销后不会再有「下一审批人」
        String nextLabel = null;
        if (!rejected && !cancelled && current != null && !current.isTerminal()) {
            if (current.getAssignStrategy() == AssignStrategyEnum.SINGLE_LEAST_BACKLOG
                    && assigneeRealName != null && !assigneeRealName.isBlank()) {
                nextLabel = assigneeRealName + "（" + current.getActorLabel() + "）";
            } else {
                nextLabel = current.getActorLabel();
            }
        }

        WorkflowDiagramVO vo = new WorkflowDiagramVO();
        vo.setBizType(bizType);
        vo.setName(def.getName());
        vo.setSteps(steps);
        vo.setCurrentStepCode(currentCode);
        vo.setNextApproverLabel(nextLabel);
        return Result.success("查询成功", vo);
    }

    /**
     * 统一待办看板：把四条审批流里「与我相关」的单据收敛成一份清单（待我处理 / 我发起的 / 已办）。
     * 供「审批中心聚合页」一屏看完，不再需要在四个页面之间来回切。
     */
    @PostMapping("/todo")
    public Result<WorkflowTodoBoardVO> todo() {
        return Result.success("查询成功", todoService.board(UserUtils.requireLoginUser()));
    }
}
