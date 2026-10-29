package com.csssl.controller;

import com.csssl.common.Result;
import com.csssl.vo.StampVO;
import com.csssl.vo.TsaPublicKeyVO;
import com.csssl.dto.TsaStampDTO;
import com.csssl.service.TsaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 可信时间戳（TSA）服务：业务系统（HIS）对签名摘要盖章，令牌由 TSA 独立 SM2 密钥签发。
 * 公钥匿名可取（验签用）；盖章动作写审计日志。
 */
@RestController
@RequestMapping("/tsa")
@RequiredArgsConstructor
public class TsaController {

    private final TsaService tsaService;

    /** 盖一枚时间戳令牌：入参为被盖时间戳的摘要（64 位十六进制） */
    @PostMapping("/stamp")
    public Result<StampVO> stamp(@RequestBody TsaStampDTO req, HttpServletRequest request) {
        TsaService.Stamp stamp = tsaService.stamp(req.getDigestHex());
        tsaService.audit(clientIp(request), stamp.serial(), req.getDigestHex().substring(0, 8), clientIp(request));
        StampVO vo = new StampVO();
        vo.setSerial(stamp.serial());
        vo.setTsaTime(stamp.tsaTime());
        vo.setTokenValue(stamp.tokenValue());
        vo.setAlgo(stamp.algo());
        vo.setPublicKeyFingerprint(tsaService.publicKeyFingerprint());
        return Result.success("时间戳盖章成功", vo);
    }

    /** TSA 公钥（PEM + SM3 指纹），供业务系统验签令牌 */
    @GetMapping("/publicKey")
    public Result<TsaPublicKeyVO> publicKey() {
        return Result.success("获取成功", tsaService.status());
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
