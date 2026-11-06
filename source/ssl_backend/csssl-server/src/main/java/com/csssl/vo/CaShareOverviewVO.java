package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 某公司根私钥门限份额总览（一次调用返回全部，避免前端多次往返）。
 *
 * <p>{@link #holders}：5 名保管人名单（份额编号 + 姓名 + 账号，不含份额明文），
 * 对有查看权者（密钥管理员/系统管理员/公司管理员）可见。</p>
 * <p>{@link #mine}：当前登录人**本人**持有的份额明文；非保管人返回 null（不报错、不泄露）。</p>
 */
@Data
public class CaShareOverviewVO {

    /** 保管人名单（按份额编号升序） */
    private List<CaSubCaHolderVO> holders;

    /** 当前登录人自身份额；非保管人为 null */
    private KeyShareMineVO mine;

    /** 门限（需几份可重组） */
    private Integer threshold;

    /** 总份数 */
    private Integer total;
}
