package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.dto.CaSystemCreateDTO;
import com.csssl.dto.CompanyUsccDTO;
import com.csssl.entity.LoginUser;
import com.csssl.service.CaSystemService;
import com.csssl.util.UserUtils;
import com.csssl.vo.CaSystemVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公司下的系统管理：系统名即证书主体 O，公司内唯一；每个系统对应一张专属二级 CA。
 */
@RestController
@RequestMapping("/admin/systems")
@RequiredArgsConstructor
public class CaSystemController {

    private final CaSystemService caSystemService;

    /**
     * 按公司列出系统（登录即可查看）。
     */
    @PostMapping("/list")
    public Result<List<CaSystemVO>> list(@RequestBody CompanyUsccDTO body) {
        return Result.success("查询成功", caSystemService.listByCompany(body.getCompanyUscc()));
    }

    /**
     * 新增系统（公司管理员 / 接入凭证管理员）。
     */
    @PreAuthorize("hasAnyAuthority('company:create', 'apiclient:manage')")
    @PostMapping("/create")
    public Result<CaSystemVO> create(@Valid @RequestBody CaSystemCreateDTO body, HttpServletRequest request) {
        LoginUser user = UserUtils.requireLoginUser();
        return Result.success("系统已创建", caSystemService.create(body, user, clientIp(request)));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
