package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.entity.CaOrg;
import com.csssl.service.AuditService;
import com.csssl.service.OrgService;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 机构域管理接口（需登录）：机构列表/详情、开机构（登记即建独立根CA）、暂停/恢复/关闭。
 * 新建与状态变更仅系统管理员；审计暂记 default 归属（审计带 org 列由下一波接入）。
 */
@RestController
@RequestMapping("/admin/orgs")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final AuditService auditService;

    /** 机构列表（附各机构根状态：有无根/SUB_CA 有效期/密钥形态 ONLINE/SEALED/SHARES） */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.success("查询成功", orgService.overview());
    }

    /** 机构详情（含根CA状态与门限份额状态） */
    @GetMapping("/{orgKey}")
    public Result<Map<String, Object>> detail(@PathVariable String orgKey) {
        CaOrg org = orgService.get(orgKey);
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("orgKey", org.getOrgKey());
        row.put("orgName", org.getOrgName());
        row.put("subjectO", org.getSubjectO());
        row.put("algorithm", org.getAlgorithm());
        row.put("status", org.getStatus());
        row.put("contact", org.getContact());
        row.put("description", org.getDescription());
        row.put("createdAt", org.getCreatedAt());
        row.put("rootStatus", orgService.overviewOf(org.getOrgKey()));
        return Result.success("查询成功", row);
    }

    /** 新建机构（仅 ADMIN）：登记后立即为该机构生成独立根CA+SUB_CA+Shamir(3,5) 门限份额 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = RoleCode.currentUser(request);
        CaOrg org = orgService.create(body.get("orgKey"), body.get("orgName"), body.get("subjectO"),
                body.get("algorithm"), body.get("contact"), body.get("description"));
        auditService.record(username, "ORG_CREATE", org.getOrgKey(),
                "新建机构 " + org.getOrgName() + "（主体O=" + org.getSubjectO() + "，算法=" + org.getAlgorithm()
                        + "），已初始化独立根CA与SUB_CA", clientIp(request));
        return Result.success("机构已创建并完成独立根CA初始化（请尽快导存 5 份门限份额）",
                Map.of("orgKey", org.getOrgKey(), "rootStatus", orgService.overviewOf(org.getOrgKey())));
    }

    /** 暂停机构（仅 ADMIN；停止对外签发由后续波次在签发链路 enforce，本波落状态） */
    @PostMapping("/{orgKey}/suspend")
    public Result<CaOrg> suspend(@PathVariable String orgKey, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = RoleCode.currentUser(request);
        CaOrg org = orgService.suspend(orgKey);
        auditService.record(username, "ORG_SUSPEND", orgKey, "暂停机构 " + org.getOrgName(), clientIp(request));
        return Result.success("机构已暂停", org);
    }

    /** 恢复机构（仅 ADMIN） */
    @PostMapping("/{orgKey}/resume")
    public Result<CaOrg> resume(@PathVariable String orgKey, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = RoleCode.currentUser(request);
        CaOrg org = orgService.resume(orgKey);
        auditService.record(username, "ORG_RESUME", orgKey, "恢复机构 " + org.getOrgName(), clientIp(request));
        return Result.success("机构已恢复", org);
    }

    /** 关闭机构（仅 ADMIN；CLOSED 终态） */
    @PostMapping("/{orgKey}/close")
    public Result<CaOrg> close(@PathVariable String orgKey, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.ADMIN);
        String username = RoleCode.currentUser(request);
        CaOrg org = orgService.close(orgKey);
        auditService.record(username, "ORG_CLOSE", orgKey, "关闭机构 " + org.getOrgName(), clientIp(request));
        return Result.success("机构已关闭", org);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
