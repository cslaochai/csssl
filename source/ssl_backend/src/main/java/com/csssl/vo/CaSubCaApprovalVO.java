package com.csssl.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 二级CA 重建申请单逐人审批记录展示项。
 */
@Data
public class CaSubCaApprovalVO {

    private String approverUsername;
    private String approverRealName;
    private String decision;
    private String comment;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime decidedAt;
}
