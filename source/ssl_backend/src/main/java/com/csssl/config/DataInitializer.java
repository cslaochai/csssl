package com.csssl.config;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.SysUser;
import com.csssl.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动初始化：确保默认管理员账号存在（首次启动创建 admin / 123456，密码 BCrypt 存储）。
 * 与 csmig 的 DataInitializer 同方案。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;

    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "admin"));
        if (count != null && count > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(BCrypt.hashpw("123456", BCrypt.gensalt()));
        admin.setRealName("系统管理员");
        admin.setRoleCode("ADMIN");
        admin.setStatus(1);
        admin.setCreatedAt(LocalDateTime.now());
        userMapper.insert(admin);
        log.warn("[初始化] 已创建默认管理员账号 admin / 123456，请登录后尽快修改口令");
    }
}
