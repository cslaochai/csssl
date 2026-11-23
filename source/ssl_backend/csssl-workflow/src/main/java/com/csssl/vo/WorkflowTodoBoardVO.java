package com.csssl.vo;

import lombok.Data;

import java.util.List;

/**
 * 统一待办看板（待我处理 / 我发起的 / 已办）。
 */
@Data
public class WorkflowTodoBoardVO {

    /**
     * 待我处理（待审批 / 待投票 / 待签发 / 待执行）
     */
    private List<WorkflowTodoVO> pending;

    /**
     * 我发起的（等别人处理中）
     */
    private List<WorkflowTodoVO> initiated;

    /**
     * 已办（我已处理完的）
     */
    private List<WorkflowTodoVO> done;
}
