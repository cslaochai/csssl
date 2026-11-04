package com.csssl.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.csssl.common.Result;
import com.csssl.dto.ApplicationAuditDTO;
import com.csssl.dto.ApplicationPageQueryDTO;
import com.csssl.dto.ApplicationSubmitDTO;
import com.csssl.dto.IdDTO;
import com.csssl.dto.IdCompanyUsccDTO;
import com.csssl.entity.ApplicationTrail;
import com.csssl.vo.CertVO;
import com.csssl.entity.CertApplication;
import com.csssl.entity.LoginUser;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysUserMapper;
import com.csssl.service.CertApplicationService;
import com.csssl.service.HisPushService;
import com.csssl.util.UserUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import org.springframework.web.bind.annotation.RestController;

/**
 * RA 申请-审核-签发接口（GM/T 0034 §5.2.2）：
 * 提交=操作员/管理员（必须指派审批人），审核=被指派的审核员/RA管理员（不得与提交人同一人），签发=操作员/管理员。
 *
 * <p>路线A数据域（服务端强制，effectiveCompany 口径）：
 * 提交时受理公司=操作者数据域（公司用户锁本人公司；全局用户显式 body.companyUscc=，缺省 default）；
 * 列表/详情按域过滤（公司用户只见本公司，全局用户 companyUscc 缺省=跨公司只读总览）；
 * approve/reject/issue/cancel 校验申请单归属公司==操作者有效域，跨公司 403——
 * 全局用户操作非 default 域申请必须显式传该公司 companyUscc，不存在写旁路。</p>
 */
@RestController
@RequestMapping("/admin/applications")
@RequiredArgsConstructor
public class CertApplicationController {

    private final CertApplicationService applicationService;

    private final HisPushService hisPushService;

    private final SysUserMapper sysUserMapper;

    /**
     * 申请单分页查询（双重收口，服务端强制：公司用户只见本公司 + 审核/录入/签发角色只见与我相关或我能处理的单；
     * 全局用户不传 companyUscc 时按角色范围跨公司只读总览。审核人参数不接受前端传入）
     */
    @PostMapping("/page")
    public Result<Page<CertApplication>> page(@Valid @RequestBody ApplicationPageQueryDTO query,
                                              HttpServletRequest request) {
        LoginUser viewer = UserUtils.requireLoginUser();
        return Result.success("查询成功", applicationService.page(query.getPageNo(), query.getPageSize(), query.getStatus(),
                query.getKeyword(), query.getCompanyUscc(), viewer));
    }

    /**
     * 申请单详情（公司用户访问他公司单按不存在处理，不泄露存在性）
     */
    @PostMapping("/detail")
    public Result<CertApplication> detail(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功",
                applicationService.detailVisible(body.getId(), UserUtils.requireLoginUser()));
    }

    /**
     * 提交证书申请（录入员代录；需 操作员/管理员；受理域=effectiveCompany，公司单校验主体 O 受控）
     */
    @PreAuthorize("hasAuthority('app:submit')")
    @PostMapping("/submit")
    public Result<CertApplication> submit(@Valid @RequestBody ApplicationSubmitDTO body,
                                          HttpServletRequest request) {
        LoginUser loginUser = UserUtils.requireLoginUser();
        String company = body.getCompanyUscc();
        return Result.success("申请提交成功", applicationService.submit(body,
                loginUser, clientIp(request), company));
    }

    /**
     * 审核批准（需 审核员/管理员；须为本单被指派人且不得与提交人同一人；申请单必须落在操作者域）
     */
    @PreAuthorize("hasAuthority('app:approve')")
    @PostMapping("/approve")
    public Result<CertApplication> approve(@Valid @RequestBody ApplicationAuditDTO body,
                                           HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        CertApplication app = applicationService.approve(body.getId(), body.getComment(),
                loginUser, clientIp(request),
                body.getCompanyUscc());
        hisPushService.pushApplication(app);
        return Result.success("已批准", app);
    }

    /**
     * 审核驳回（需 审核员/管理员；须为本单被指派人且不得与提交人同一人；申请单必须落在操作者域）
     */
    @PreAuthorize("hasAuthority('app:reject')")
    @PostMapping("/reject")
    public Result<CertApplication> reject(@Valid @RequestBody ApplicationAuditDTO body,
                                          HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        CertApplication app = applicationService.reject(body.getId(), body.getComment(),
                loginUser, clientIp(request),
                body.getCompanyUscc());
        hisPushService.pushApplication(app);
        return Result.success("已驳回", app);
    }

    /**
     * 按申请签发证书（需 操作员/管理员；复用模板+公钥，私钥申请方自持；落到申请单受理公司域）
     */
    @PreAuthorize("hasAuthority('app:issue')")
    @PostMapping("/issue")
    public Result<CertVO> issue(@RequestBody IdCompanyUsccDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        CertVO vo = applicationService.issue(body.getId(),
                loginUser, clientIp(request),
                body.getCompanyUscc());
        // 签发已提交：按最新申请单状态推送外部系统（ISSUED 报文带证书 PEM，供其自动回填）
        hisPushService.pushApplication(applicationService.detail(body.getId()));
        return Result.success("签发成功", vo);
    }

    /**
     * 撤销申请（需 操作员/管理员；仅提交人本人或系统管理员可撤销；申请单必须落在操作者域）
     */
    @PreAuthorize("hasAuthority('app:cancel')")
    @PostMapping("/cancel")
    public Result<CertApplication> cancel(@RequestBody IdCompanyUsccDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        CertApplication app = applicationService.cancel(body.getId(),
                loginUser, clientIp(request),
                body.getCompanyUscc());
        hisPushService.pushApplication(app);
        return Result.success("已撤销", app);
    }

    /**
     * 审批人名单（提交表单「指定审批人」下拉用；受限，不依赖 user:list）。
     * CA 平台公司级统管，审批人均为公司（平台）级人员，与受理公司无关，不做任何公司过滤——
     * 直接返回全部启用状态的审核员(APPROVER)/RA管理员(RA_ADMIN)。
     */
    @PreAuthorize("hasAuthority('app:listApprovers')")
    @PostMapping("/approvers")
    public Result<List<SysUser>> listApprovers() {
        List<SysUser> list = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getRoleCode, "APPROVER", "RA_ADMIN")
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getId));
        list.forEach(u -> u.setPassword(null));
        return Result.success("查询成功", list);
    }

    /**
     * 单据审批轨迹（按步时间正序，面向业务用户）
     */
    @PostMapping("/trail")
    public Result<List<ApplicationTrail>> listTrail(@RequestBody IdDTO body) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        return Result.success("查询成功", applicationService.listTrail(body.getId()));
    }

    /**
     * 催办：向被指派审批人推送站内信并补 URGE 轨迹
     */
    @PreAuthorize("hasAuthority('app:urge')")
    @PostMapping("/urge")
    public Result<Void> urge(@RequestBody IdDTO body, HttpServletRequest request) {
        if (body.getId() == null) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        LoginUser loginUser = UserUtils.requireLoginUser();
        applicationService.urge(body.getId(), loginUser, clientIp(request));
        return Result.success("已催办", null);
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
