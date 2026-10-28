package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CRL 发布状态（全库单行，id=1）：取代 store-dir/crl-state.json，
 * 行锁（SELECT ... FOR UPDATE）保证并发/多实例下 crl_number 单调与判重复用一致。
 */
@Data
@TableName("ssl_crl_state")
public class CrlState {

    /** 单行约定：全库唯一 CRL 发布状态行 */
    public static final long SINGLE_ROW_ID = 1L;

    @TableId(type = IdType.INPUT)
    private Long id;

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
