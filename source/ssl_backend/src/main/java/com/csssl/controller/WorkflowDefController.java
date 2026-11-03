package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.workflow.WorkflowDefService;
import com.csssl.workflow.dto.DefJson;
import com.csssl.workflow.model.FlowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 流程定义 CRUD —— 自研轻量引擎的定义侧，供前端拖拽设计器使用。
 *
 * <p>权限口径：流程设计器属于系统级能力，授予 ADMIN 与 SECURITY_ADMIN。</p>
 */
@RestController
@RequestMapping("/admin/workflow/defs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ADMIN')")
public class WorkflowDefController {

    private final WorkflowDefService defService;

    /** 列出所有流程定义（含草稿） */
    @GetMapping
    public Result<List<FlowDefinition>> list() {
        return Result.success(defService.list());
    }

    /** 按 flowCode 查已发布定义 */
    @GetMapping("/published/{flowCode}")
    public Result<FlowDefinition> getPublished(@PathVariable String flowCode) {
        FlowDefinition def = defService.getPublished(flowCode);
        if (def == null) {
            return Result.error(404, "流程定义不存在或未发布：" + flowCode);
        }
        return Result.success(def);
    }

    /** 设计器回显：定义 + 节点 + 跳转 */
    @GetMapping("/{id}/design")
    public Result<DefJson> queryDesign(@PathVariable Long id) {
        return Result.success(defService.queryDesign(id));
    }

    /** 保存（新增或更新），保存后为草稿 */
    @PostMapping("/save")
    public Result<Long> save(@RequestBody DefJson defJson) {
        return Result.success(defService.save(defJson));
    }

    /** 发布 */
    @PostMapping("/{id}/publish")
    public Result<Boolean> publish(@PathVariable Long id) {
        return Result.success(defService.publish(id));
    }

    /** 取消发布 */
    @PostMapping("/{id}/unpublish")
    public Result<Boolean> unpublish(@PathVariable Long id) {
        return Result.success(defService.unpublish(id));
    }

    /** 删除（级联删除节点与跳转） */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(defService.delete(id));
    }

    /** 导出设计 JSON */
    @GetMapping("/{id}/export")
    public Result<String> export(@PathVariable Long id) {
        return Result.success(defService.export(id));
    }
}
