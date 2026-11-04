package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.CodeDTO;
import com.csssl.dto.IdDTO;
import com.csssl.entity.CaCompany;
import com.csssl.service.AuditService;
import com.csssl.util.UserUtils;
import com.csssl.vo.UserSimpleVO;
import com.csssl.workflow.WorkflowDefService;
import com.csssl.workflow.dto.DefIdRequest;
import com.csssl.workflow.dto.DefJson;
import com.csssl.workflow.model.FlowDefinition;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 流程定义 CRUD —— 自研轻量引擎的定义侧，供前端拖拽设计器使用。
 *
 * <p>权限口径：流程设计器属于系统级能力，授予 ADMIN 与 SECURITY_ADMIN。</p>
 *
 * <p>接口约定：URL 路径中不出现雪花 ID（避免前端精度丢失和日志噪音），
 * 读写一律通过 query 参数或请求体传递。</p>
 */
@RestController
@RequestMapping("/admin/workflow/defs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SECURITY_ADMIN')")
public class WorkflowDefController {

    private final WorkflowDefService defService;
    private final AuditService auditService;

    /**
     * 列出所有流程定义（含草稿）
     */
    @PostMapping("/list")
    public Result<List<FlowDefinition>> list() {
        return Result.success(defService.list());
    }

    /**
     * 设计器「固定人员」选人下拉：全部启用用户
     */
    @PostMapping("/users")
    public Result<List<UserSimpleVO>> users() {
        return Result.success(defService.listAssignableUsers());
    }

    /**
     * 按 flowCode 查已发布定义
     */
    @PostMapping("/published")
    public Result<FlowDefinition> getPublished(@RequestBody CodeDTO body) {
        String flowCode = body.getFlowCode() != null ? body.getFlowCode() : body.getCode();
        if (flowCode == null || flowCode.isBlank()) {
            throw new IllegalArgumentException("参数 flowCode 不能为空");
        }
        FlowDefinition def = defService.getPublished(flowCode);
        if (def == null) {
            return Result.error(404, "流程定义不存在或未发布：" + flowCode);
        }
        return Result.success(def);
    }

    /**
     * 设计器回显：定义 + 节点 + 跳转
     */
    @PostMapping("/design")
    public Result<DefJson> queryDesign(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success(defService.queryDesign(body.getId()));
    }

    /**
     * 保存（新增或更新），保存后为草稿；返回的 id 按字符串下发，防止前端精度丢失
     */
    @PostMapping("/save")
    public Result<String> save(@RequestBody DefJson defJson, HttpServletRequest request) {
        Long id = defService.save(defJson);
        auditService.record(UserUtils.requireLoginUser(), "WORKFLOW_DEF_SAVE", String.valueOf(id),
                "保存流程草稿：" + defJson.getFlowCode() + "（" + defJson.getFlowName() + "）",
                clientIp(request), "default");
        return Result.success(String.valueOf(id));
    }

    /**
     * 保存并发布（设计器用）：一次事务内先保存当前设计，再发布
     */
    @PostMapping("/save-publish")
    public Result<String> saveAndPublish(@RequestBody DefJson defJson, HttpServletRequest request) {
        Long id = defService.saveAndPublish(defJson);
        auditService.record(UserUtils.requireLoginUser(), "WORKFLOW_DEF_PUBLISH", String.valueOf(id),
                "保存并发布流程：" + defJson.getFlowCode() + "（" + defJson.getFlowName() + "）",
                clientIp(request), "default");
        return Result.success(String.valueOf(id));
    }

    /**
     * 发布（列表页用：仅切换发布状态）
     */
    @PostMapping("/publish")
    public Result<Boolean> publish(@RequestBody DefIdRequest req, HttpServletRequest request) {
        defService.publish(req.getId());
        DefJson design = defService.queryDesign(req.getId());
        auditService.record(UserUtils.requireLoginUser(), "WORKFLOW_DEF_PUBLISH", String.valueOf(req.getId()),
                "发布流程：" + design.getFlowCode() + "（" + design.getFlowName() + "）",
                clientIp(request), "default");
        return Result.success(true);
    }

    /**
     * 取消发布
     */
    @PostMapping("/unpublish")
    public Result<Boolean> unpublish(@RequestBody DefIdRequest req, HttpServletRequest request) {
        defService.unpublish(req.getId());
        auditService.record(UserUtils.requireLoginUser(), "WORKFLOW_DEF_UNPUBLISH", String.valueOf(req.getId()),
                "撤销发布流程 id=" + req.getId(), clientIp(request), "default");
        return Result.success(true);
    }

    /**
     * 删除（级联删除节点与跳转）
     */
    @PostMapping("/delete")
    public Result<Boolean> delete(@RequestBody DefIdRequest req, HttpServletRequest request) {
        defService.delete(req.getId());
        auditService.record(UserUtils.requireLoginUser(), "WORKFLOW_DEF_DELETE", String.valueOf(req.getId()),
                "删除流程定义 id=" + req.getId(), clientIp(request), "default");
        return Result.success(true);
    }

    /**
     * 导出设计 JSON
     */
    @PostMapping("/export")
    public Result<String> export(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success(defService.export(body.getId()));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
