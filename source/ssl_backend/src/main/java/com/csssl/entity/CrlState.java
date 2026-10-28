package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CRL 发布状态：路线A 多机构后为「每机构一行」（org_key 逻辑唯一，行锁 SELECT FOR UPDATE by org_key）。
 * 存量单行（id=1，org_key='default'）即院内默认域发布状态，语义与原「全库单行」完全一致；
 * 新机构首次发布时按 max(id)+1 补建本机构行（id 列无 AUTO_INCREMENT，代码显式生成）。
 */
@Data
@TableName("ssl_crl_state")
public class CrlState {

    /** default 机构存量约定行 id（历史单行时代固定 id=1，保持不动） */
    public static final long SINGLE_ROW_ID = 1L;

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属机构（每机构独立 CRL 发布状态行：编号单调与判重指纹均 per-org） */
    private String orgKey;

    /** 当前全量 CRL 编号（单调递增，仅内容变化/到期续发时递增） */
    private Long crlNumber;

    /** 上次发布 CRL 的「吊销+挂起序列集合」SHA-256 指纹（判重：未变则复用编号与已发布 CRL） */
    private String fingerprint;

    /** 上次发布时间 */
    private LocalDateTime thisUpdate;

    /** 下次强制更新时间（进入续期窗口由定时任务主动续发） */
    private LocalDateTime nextUpdate;

    /** 增量 CRL 指向的基础全量编号 */
    private Long deltaBaseNumber;

    /** 行更新时间（DDL ON UPDATE CURRENT_TIMESTAMP 维护，代码侧只读） */
    private LocalDateTime updatedAt;
}
