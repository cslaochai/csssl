package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.service.WorkflowTodoService;
import com.csssl.util.UserUtils;
import com.csssl.vo.WorkflowDiagramVO;
import com.csssl.vo.WorkflowStepStateVO;
import com.csssl.vo.WorkflowTodoBoardVO;
import com.csssl.workflow.AssignStrategy;
import com.csssl.workflow.WorkflowDefinition;
import com.csssl.workflow.WorkflowRegistry;
import com.csssl.workflow.WorkflowStep;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 全部工作流定义（用于总览页） */
    @GetMapping
    public Result<List<WorkflowDefinition>> list() {
        return Result.success("查询成功", registry.all());
    }

    /**
     * 按业务类型 + 当前状态推导流程图（含当前步高亮、当前待处理人提示）。
     *
     * <p>assigneeRealName 用于「当前停留步骤」为 {@link AssignStrategy#SINGLE_LEAST_BACKLOG} 时，
     * 把抽象的角色标签换成具体人（如「张三（审核员 / RA管理员）」）——
     * 这正是"提交后看不到下一个审批人是谁"的答案：卡片上停留的那一步，就是现在该他动手。</p>
     */
    @GetMapping("/diagram")
    public Result<WorkflowDiagramVO> diagram(@RequestParam String bizType,
                                    @RequestParam String status,
                                    @RequestParam(required = false) String assigneeRealName) {
        WorkflowDefinition def = registry.get(bizType);
        if (def == null) {
            throw new IllegalArgumentException("未知业务类型：" + bizType);
        }
        WorkflowStep current = def.currentStep(status);
        String currentCode = current == null ? null : current.getCode();

        List<WorkflowStepStateVO> steps = new ArrayList<>();
        int currentIdx = def.indexOfStep(currentCode);
        for (WorkflowStep s : def.getSteps()) {
            String state;
            if (s.isTerminal()) {
                state = "DONE";
            } else if (currentCode == null) {
                state = "PENDING";
            } else if (s.getCode().equals(currentCode)) {
                state = "CURRENT";
            } else {
                int si = def.indexOfStep(s.getCode());
                state = si >= 0 && si < currentIdx ? "DONE" : "PENDING";
            }
            steps.add(new WorkflowStepStateVO(s.getCode(), s.getName(), s.getActorLabel(),
                    s.getAssignStrategy().name(), s.isTerminal(), state));
        }

        // 当前该谁处理：停留在哪一步，就由这一步的角色/具体人接手（而非再往后一步）
        String nextLabel = null;
        if (current != null && !current.isTerminal()) {
            if (current.getAssignStrategy() == AssignStrategy.SINGLE_LEAST_BACKLOG
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
    @GetMapping("/todo")
    public Result<WorkflowTodoBoardVO> todo() {
        return Result.success("查询成功", todoService.board(UserUtils.requireLoginUser()));
    }
}
