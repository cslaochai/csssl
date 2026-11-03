package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.workflow.WorkflowEngineService;
import com.csssl.workflow.dto.StartRequest;
import com.csssl.workflow.dto.TaskVO;
import com.csssl.workflow.model.FlowHisTask;
import com.csssl.workflow.model.FlowInstance;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /** 发起流程 */
    @PostMapping("/instances/start")
    public Result<FlowInstance> start(@RequestBody StartRequest req) {
        return Result.success(engineService.start(req));
    }

    /** 通过待办 */
    @PostMapping("/tasks/{taskId}/approve")
    public Result<FlowInstance> approve(@PathVariable Long taskId, @RequestBody(required = false) ActionBody body) {
        return Result.success(engineService.approve(taskId, body == null ? null : body.getMessage()));
    }

    /** 驳回待办 */
    @PostMapping("/tasks/{taskId}/reject")
    public Result<FlowInstance> reject(@PathVariable Long taskId, @RequestBody(required = false) ActionBody body) {
        return Result.success(engineService.reject(taskId, body == null ? null : body.getMessage()));
    }

    /** 撤回流程实例 */
    @PostMapping("/instances/{instanceId}/withdraw")
    public Result<FlowInstance> withdraw(@PathVariable Long instanceId) {
        return Result.success(engineService.withdraw(instanceId));
    }

    /** 我的待办 */
    @GetMapping("/tasks/todo")
    public Result<List<TaskVO>> todo() {
        return Result.success(engineService.myPendingTasks());
    }

    /** 单据审批轨迹 */
    @GetMapping("/instances/history/{businessId}")
    public Result<List<FlowHisTask>> history(@PathVariable String businessId) {
        return Result.success(engineService.history(businessId));
    }

    @Data
    public static class ActionBody {
        private String message;
    }
}
