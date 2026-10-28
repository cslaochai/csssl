package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.CertVo;
import com.csssl.entity.CertApplication;
import com.csssl.service.CertApplicationService;
import com.csssl.service.PermissionService;
import com.csssl.util.PermissionCatalog;
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
 *
 * <p>路线A数据域（服务端强制，effectiveOrg 口径）：
 * 提交时受理机构=操作者数据域（机构用户锁本人机构；全局用户显式 ?orgKey=，缺省 default）；
 * 列表/详情按域过滤（机构用户只见本机构，全局用户 orgKey 缺省=跨机构只读总览）；
 * approve/reject/issue/cancel 校验申请单归属机构==操作者有效域，跨机构 403——
 * 全局用户操作非 default 域申请必须显式传该机构 orgKey，不存在写旁路。</p>
 */
@RestController
@RequestMapping("/admin/applications")
@RequiredArgsConstructor
public class CertApplicationController {

    private final CertApplicationService applicationService;
    private final PermissionService permissionService;

    /** 申请单分页查询（登录即可查看；机构用户强制本机构，全局用户可按 ?orgKey= 过滤，不传=全量只读） */
    @GetMapping
    public Result<Page<CertApplication>> page(@RequestParam(defaultValue = "1") long pageNo,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String orgKey,
                                              HttpServletRequest request) {
        return Result.success("查询成功", applicationService.page(pageNo, pageSize, status, keyword,
                RoleCode.readOrgScope(request, orgKey)));
    }

    /** 申请单详情（机构用户访问他机构单按不存在处理，不泄露存在性） */
    @GetMapping("/{id}")
    public Result<CertApplication> detail(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("查询成功",
                applicationService.detailVisible(id, RoleCode.currentOrg(request)));
    }

    /** 提交证书申请（录入员代录；需 操作员/管理员；受理域=effectiveOrg，机构单校验主体 O 受控） */
    @PostMapping
    public Result<CertApplication> submit(@RequestBody Map<String, String> body,
                                          @RequestParam(required = false) String orgKey,
                                          HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.APP_SUBMIT);
        String org = RoleCode.effectiveOrg(request, orgKey == null || orgKey.isBlank() ? body.get("orgKey") : orgKey);
        return Result.success("申请提交成功", applicationService.submit(body,
                RoleCode.currentUser(request), clientIp(request), org));
    }

    /** 审核批准（需 审核员/管理员；不得与提交人同一人；申请单必须落在操作者域） */
    @PostMapping("/{id}/approve")
    public Result<CertApplication> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                           @RequestParam(required = false) String orgKey,
                                           HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.APP_APPROVE);
        String comment = body == null ? null : body.get("comment");
        return Result.success("已批准", applicationService.approve(id, comment,
                RoleCode.currentUser(request), clientIp(request),
                RoleCode.effectiveOrg(request, orgKey)));
    }

    /** 审核驳回（需 审核员/管理员；不得与提交人同一人；申请单必须落在操作者域） */
    @PostMapping("/{id}/reject")
    public Result<CertApplication> reject(@PathVariable Long id, @RequestBody Map<String, String> body,
                                          @RequestParam(required = false) String orgKey,
                                          HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.APP_REJECT);
        return Result.success("已驳回", applicationService.reject(id, body.get("comment"),
                RoleCode.currentUser(request), clientIp(request),
                RoleCode.effectiveOrg(request, orgKey)));
    }

    /** 按申请签发证书（需 操作员/管理员；复用模板+公钥，私钥申请方自持；落到申请单受理机构域） */
    @PostMapping("/{id}/issue")
    public Result<CertVo> issue(@PathVariable Long id, @RequestParam(required = false) String orgKey,
                                HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.APP_ISSUE);
        return Result.success("签发成功", applicationService.issue(id,
                RoleCode.currentUser(request), clientIp(request),
                RoleCode.effectiveOrg(request, orgKey)));
    }

    /** 撤销申请（需 操作员/管理员；仅提交人本人或系统管理员可撤销；申请单必须落在操作者域） */
    @PostMapping("/{id}/cancel")
    public Result<CertApplication> cancel(@PathVariable Long id, @RequestParam(required = false) String orgKey,
                                          HttpServletRequest request) {
        permissionService.require(request, PermissionCatalog.APP_CANCEL);
        return Result.success("已撤销", applicationService.cancel(id,
                RoleCode.currentUser(request), RoleCode.currentRole(request), clientIp(request),
                RoleCode.effectiveOrg(request, orgKey)));
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
