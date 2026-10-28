package com.csssl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限点授权（角色配置页勾选结果）。
 *
 * <p>权限点分两类：MENU=页面菜单（控制侧栏与路由进入），ACTION=操作按钮
 * （控制按钮显隐与接口校验）。授权集合必须落在角色法定上限内，
 * 服务端保存时逐项校验，读取时与上限再取交集（DB 被篡改也不能越权）。</p>
 */
@Data
@TableName("ssl_role_perm")
public class RolePerm {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码 */
    private String roleCode;

    /** 权限点编码（如 menu:certs / cert:issue） */
    private String permCode;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
