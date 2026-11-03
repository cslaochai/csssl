package com.csssl.workflow;

/**
 * 步骤分派策略：决定「下一步由谁处理」。
 */
public enum AssignStrategy {
    /** 自动分派到积压最少的具体处理人（进入该步时按角色池 + 待办最少计算并落库 assignee） */
    SINGLE_LEAST_BACKLOG,
    /** 角色池：任何持有该角色/权限者皆可处理，不锁定到具体人 */
    POOL,
    /** 多指派 / 投票（如 SUB_CA 重建的 3-of-5 密钥保管人） */
    MULTI_VOTE
}
