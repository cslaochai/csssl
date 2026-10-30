package com.csssl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.ApplicationAuditDTO;
import com.csssl.dto.ApplicationPageQueryDTO;
import com.csssl.dto.ApplicationSubmitDTO;
import com.csssl.vo.CertVO;
import com.csssl.entity.CertApplication;
import com.csssl.entity.LoginUser;
import com.csssl.service.CertApplicationService;
import com.csssl.util.TenantScope;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RA 申请-审核-签发接口（GM/T 0034 §5.2.2）：
 * 提交=操作员/管理员，审核=审核员/管理员（不得与提交人同一人），签发=操作员/管理员。
 *
 * <p>路线A数据域（服务端强制，effectiveTenant 口径）：
 * 提交时受理租户=操作者数据域（租户用户锁本人租户；全局用户显式 ?tenantKey=，缺省 default）；
 * 列表/详情按域过滤（租户用户只见本租户，全局用户 tenantKey 缺省=跨租户只读总览）；
 * approve/reject/issue/cancel 校验申请单归属租户==操作者有效域，跨租户 403——
 * 全局用户操作非 default 域申请必须显式传该租户 tenantKey，不存在写旁路。</p>
 */
@RestController
@RequestMapping("/admin/applications")
@RequiredArgsConstructor
public class CertApplicationController {

    private final CertApplicationService applicationService;

    /** 申请单分页查询（登录即可查看；租户用户强制本租户，全局用户可按 ?tenantKey= 过滤，不传=全量只读） */
    @GetMapping
    public Result<Page<CertApplication>> page(@Valid ApplicationPageQueryDTO query,
                                              HttpServletRequest request) {
        return Result.success("查询成功", applicationService.page(query.getPageNo(), query.getPageSize(), query.getStatus(),
                query.getKeyword(), TenantScope.readTenantScope(UserUtils.requireLoginUser().getTenantKey(), query.getTenantKey())));
    }

    /** 申请单详情（租户用户访问他租户单按不存在处理，不泄露存在性） */
    @GetMapping("/{id}")
    public Result<CertApplication> detail(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("查询成功",
                applicationService.detailVisible(id, UserUtils.requireLoginUser().getTenantKey()));
    }

    /** 提交证书申请（录入员代录；需 操作员/管理员；受理域=effectiveTenant，租户单校验主体 O 受控） */
    @PreAuthorize("hasAuthority('app:submit')")
    @PostMapping
    public Result<CertApplication> submit(@Valid @RequestBody ApplicationSubmitDTO body,
                                          @RequestParam(required = false) String tenantKey,
                                          HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        String tenant = TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey == null || tenantKey.isBlank() ? body.getTenantKey() : tenantKey);
        return Result.success("申请提交成功", applicationService.submit(body,
                loginUser.getUsername(), loginUser.getRealName(), clientIp(request), tenant));
    }

    /** 审核批准（需 审核员/管理员；不得与提交人同一人；申请单必须落在操作者域） */
    @PreAuthorize("hasAuthority('app:approve')")
    @PostMapping("/{id}/approve")
    public Result<CertApplication> approve(@PathVariable Long id, @Valid @RequestBody(required = false) ApplicationAuditDTO body,
                                           @RequestParam(required = false) String tenantKey,
                                           HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        String comment = body == null ? null : body.getComment();
        return Result.success("已批准", applicationService.approve(id, comment,
                loginUser.getUsername(), loginUser.getRealName(), clientIp(request),
                TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey)));
    }

    /** 审核驳回（需 审核员/管理员；不得与提交人同一人；申请单必须落在操作者域） */
    @PreAuthorize("hasAuthority('app:reject')")
    @PostMapping("/{id}/reject")
    public Result<CertApplication> reject(@PathVariable Long id, @Valid @RequestBody ApplicationAuditDTO body,
                                          @RequestParam(required = false) String tenantKey,
                                          HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("已驳回", applicationService.reject(id, body.getComment(),
                loginUser.getUsername(), loginUser.getRealName(), clientIp(request),
                TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey)));
    }

    /** 按申请签发证书（需 操作员/管理员；复用模板+公钥，私钥申请方自持；落到申请单受理租户域） */
    @PreAuthorize("hasAuthority('app:issue')")
    @PostMapping("/{id}/issue")
    public Result<CertVO> issue(@PathVariable Long id, @RequestParam(required = false) String tenantKey,
                                HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("签发成功", applicationService.issue(id,
                loginUser.getUsername(), clientIp(request),
                TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey)));
    }

    /** 撤销申请（需 操作员/管理员；仅提交人本人或系统管理员可撤销；申请单必须落在操作者域） */
    @PreAuthorize("hasAuthority('app:cancel')")
    @PostMapping("/{id}/cancel")
    public Result<CertApplication> cancel(@PathVariable Long id, @RequestParam(required = false) String tenantKey,
                                          HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        return Result.success("已撤销", applicationService.cancel(id,
                loginUser.getUsername(), loginUser.getRoleCode(), clientIp(request),
                TenantScope.requireExplicitTenant(loginUser.getTenantKey(), tenantKey)));
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
