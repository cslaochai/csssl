package com.csssl.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.csssl.entity.LoginUser;
import com.csssl.util.UserUtils;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通用审计列自动填写（insert 填创建人四件套，update 填修改人四件套）。
 *
 * <p>口径只在这一处：操作人 ID 与真实姓名一律取自 {@link LoginUser}（TokenAuthFilter 校验签名并回库后写入，
 * 前端伪造不了），业务代码不参与填写，因此不可能出现「某张表忘了落姓名」或「姓名拿账号兜底」。
 * 没有登录态的行（定时任务、建根自举、对外 API 通道）按既定口径处理：user_id 留空、姓名写 {@link #SYSTEM_NAME}，
 * 不臆造一个 id=0 的账号。</p>
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    /**
     * 系统自动写入行的操作人姓名（user_id 为空时的唯一可读标识）
     */
    public static final String SYSTEM_NAME = "系统";

    @Override
    public void insertFill(MetaObject metaObject) {
        LoginUser loginUser = UserUtils.getLoginUser();
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createdByUserId", Long.class, loginUser == null ? null : loginUser.getUserId());
        strictInsertFill(metaObject, "updatedByUserId", Long.class, loginUser == null ? null : loginUser.getUserId());
        String name = loginUser == null ? SYSTEM_NAME : loginUser.getRealName();
        strictInsertFill(metaObject, "createdByName", String.class, name);
        strictInsertFill(metaObject, "updatedByName", String.class, name);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        LoginUser loginUser = UserUtils.getLoginUser();
        if (loginUser == null) {
            // 定时任务/自举等无登录态的写入不改写「最后修改人」：宁可留上一个真人，也不把系统动作伪装成某人改的
            return;
        }
        // 加载后回写的实体带着旧值，strictUpdateFill 见非空就不填会留下旧修改人，故这里无条件覆盖
        setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        setFieldValByName("updatedByUserId", loginUser.getUserId(), metaObject);
        setFieldValByName("updatedByName", loginUser.getRealName(), metaObject);
    }
}
