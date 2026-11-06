package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 二级CA 重建申请单逐人审批记录展示项。
 *
 * <p>只出真实姓名，不出登录账号（同 {@link CaSubCaHolderVO} 口径）。
 */
@Data
public class CaSubCaApprovalVO {

    private String approverRealName;
    private String decision;
    private String comment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime decidedAt;
}
