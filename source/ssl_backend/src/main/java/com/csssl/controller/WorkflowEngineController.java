package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.IdDTO;
import com.csssl.dto.IdMessageDTO;
import com.csssl.dto.WorkflowCandidatesDTO;
import com.csssl.dto.WorkflowHistoryDTO;
import com.csssl.entity.SysUser;
import com.csssl.enums.RoleCodeEnum;
import com.csssl.workflow.WorkflowEngineService;
import com.csssl.workflow.dto.StartRequest;
import com.csssl.workflow.dto.TaskVO;
import com.csssl.workflow.model.FlowHisTask;
import com.csssl.workflow.model.FlowInstance;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流引擎运行时接口：发起、审批、驳回、撤回、待办、轨迹。
 *
 * <p>并发语义：审批/驳回走条件更新（CAS），同一待办被两人同时提交时只有一个成功，
 * 另一个收到「该待办已被其他人处理」。</p>
 */
@RestController
@RequestMapping("/admin/workflow")
@RequiredArgsConstructor
public class WorkflowEngineController {

    private final WorkflowEngineService engineService;

    /**
     * 发起流程
     */
    @PostMapping("/instances/start")
    public Result<FlowInstance> start(@RequestBody StartRequest req) {
        return Result.success(engineService.start(req));
    }

    /**
     * 通过待办
     */
    @PostMapping("/approve-task")
    public Result<FlowInstance> approve(@Valid @RequestBody IdMessageDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success(engineService.approve(body.getId(), body.getMessage()));
    }

    /**
     * 驳回待办
     */
    @PostMapping("/reject-task")
    public Result<FlowInstance> reject(@Valid @RequestBody IdMessageDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success(engineService.reject(body.getId(), body.getMessage()));
    }

    /**
     * 撤回流程实例
     */
    @PostMapping("/withdraw")
    public Result<FlowInstance> withdraw(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success(engineService.withdraw(body.getId()));
    }

    /**
     * 我的待办
     */
    @PostMapping("/tasks/todo")
    public Result<List<TaskVO>> todo() {
        return Result.success(engineService.myPendingTasks());
    }

    /**
     * 单据审批轨迹
     */
    @PostMapping("/history")
    public Result<List<FlowHisTask>> history(@RequestBody WorkflowHistoryDTO body) {
        if (body.getBusinessId() == null || body.getBusinessId().isBlank()) {
            throw new IllegalArgumentException("参数 businessId 不能为空");
        }
        return Result.success(engineService.history(body.getBusinessId()));
    }

    /**
     * 提交表单的审批人候选：按该流程首个审批节点的权限标识（role:XXX / user:xxx）解析，
     * 前端据此做「提交时选人」下拉，服务端起流程时会再校验所选人是否在候选范围内。
     */
    @PostMapping("/candidates")
    public Result<List<CandidateVO>> candidates(@RequestBody WorkflowCandidatesDTO body) {
        if (body.getFlowCode() == null || body.getFlowCode().isBlank()) {
            throw new IllegalArgumentException("参数 flowCode 不能为空");
        }
        List<SysUser> list = engineService.firstNodeCandidates(body.getFlowCode());
        List<CandidateVO> vos = list.stream().map(u -> {
            RoleCodeEnum rc = RoleCodeEnum.fromCode(u.getRoleCode());
            CandidateVO v = new CandidateVO();
            v.setId(u.getId());
            v.setUsername(u.getUsername());
            v.setRealName(u.getRealName());
            v.setRoleCode(u.getRoleCode());
            v.setRoleName(rc == null ? u.getRoleCode() : rc.getRoleName());
            return v;
        }).collect(Collectors.toList());
        return Result.success("查询成功", vos);
    }

    @Data
    public static class CandidateVO {
        private Long id;
        private String username;
        private String realName;
        private String roleCode;
        /**
         * 角色中文名（由 RoleCodeEnum 映射，前端下拉显示用）
         */
        private String roleName;
    }
}
