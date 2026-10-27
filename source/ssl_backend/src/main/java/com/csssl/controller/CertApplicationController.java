package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.CertVo;
import com.csssl.entity.CertApplication;
import com.csssl.service.CertApplicationService;
import com.csssl.util.RoleCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RA 申请-审核-签发接口（GM/T 0034 §5.2.2）：
 * 提交=操作员/管理员，审核=审核员/管理员（不得与提交人同一人），签发=操作员/管理员。
 */
@RestController
@RequestMapping("/admin/applications")
@RequiredArgsConstructor
public class CertApplicationController {

    private final CertApplicationService applicationService;

    /** 申请单分页查询（登录即可查看） */
    @GetMapping
    public Result<Page<CertApplication>> page(@RequestParam(defaultValue = "1") long pageNo,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword) {
        return Result.success("查询成功", applicationService.page(pageNo, pageSize, status, keyword));
    }

    /** 申请单详情 */
    @GetMapping("/{id}")
    public Result<CertApplication> detail(@PathVariable Long id) {
        return Result.success("查询成功", applicationService.detail(id));
    }

    /** 提交证书申请（录入员代录；需 操作员/管理员） */
    @PostMapping
    public Result<CertApplication> submit(@RequestBody Map<String, String> body, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        return Result.success("申请提交成功", applicationService.submit(body,
                RoleCode.currentUser(request), clientIp(request)));
    }

    /** 审核批准（需 审核员/管理员；不得与提交人同一人） */
    @PostMapping("/{id}/approve")
    public Result<CertApplication> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                           HttpServletRequest request) {
        RoleCode.require(request, RoleCode.APPROVER, RoleCode.ADMIN);
        String comment = body == null ? null : body.get("comment");
        return Result.success("已批准", applicationService.approve(id, comment,
                RoleCode.currentUser(request), clientIp(request)));
    }

    /** 审核驳回（需 审核员/管理员；不得与提交人同一人） */
    @PostMapping("/{id}/reject")
    public Result<CertApplication> reject(@PathVariable Long id, @RequestBody Map<String, String> body,
                                          HttpServletRequest request) {
        RoleCode.require(request, RoleCode.APPROVER, RoleCode.ADMIN);
        return Result.success("已驳回", applicationService.reject(id, body.get("comment"),
                RoleCode.currentUser(request), clientIp(request)));
    }

    /** 按申请签发证书（需 操作员/管理员；复用模板+公钥，私钥申请方自持） */
    @PostMapping("/{id}/issue")
    public Result<CertVo> issue(@PathVariable Long id, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        return Result.success("签发成功", applicationService.issue(id,
                RoleCode.currentUser(request), clientIp(request)));
    }

    /** 撤销申请（需 操作员/管理员） */
    @PostMapping("/{id}/cancel")
    public Result<CertApplication> cancel(@PathVariable Long id, HttpServletRequest request) {
        RoleCode.require(request, RoleCode.OPERATOR, RoleCode.ADMIN);
        return Result.success("已撤销", applicationService.cancel(id,
                RoleCode.currentUser(request), clientIp(request)));
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
