package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 根私钥门限份额保管人绑定：建公司/建根时指定 5 名保管人，根私钥 Shamir(3,5) 拆 5 份，一人一份、落表绑定。
 *
 * <p>安全约定：仅保管人本人可读取自身份额（{@code holder_username} 与登录账号一致）；份额 JSON 即
 * {@link com.csssl.util.ShamirUtil.Share} 序列化，任意 3 份可恢复根私钥。跨公司不可互用（份额按公司目录拆分）。</p>
 */
@Data
@TableName("ssl_company_key_share")
public class CompanyKeyShare {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 公司标识（统一社会信用代码）
     */
    private String companyUscc;

    /**
     * 份额编号（1~5，与 Shamir 拆分顺序一致）
     */
    private int shareIndex;

    /**
     * 份额保管人账号（建根时指定，一人一份）
     */
    private String holderUsername;

    /**
     * 保管人真实姓名（展示）
     */
    private String holderRealName;

    /**
     * 该保管人持有的份额 JSON（index/total/threshold/yHex）
     */
    private String shareJson;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
