package com.csssl.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 工作流表初始化器。
 *
 * <p>执行 {@code db/warm-flow.sql}（全部为 {@code CREATE TABLE IF NOT EXISTS}），
 * 只建 {@code flow_*} 前缀的流程表，不触碰任何业务表，可重复执行。
 * 应用启动早期执行，晚于它的流程种子初始化才可安全读写流程表。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class WorkflowSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("db/warm-flow.sql").getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                    continue;
                }
                sb.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    execute(sb.toString());
                    sb.setLength(0);
                }
            }
        } catch (Exception e) {
            log.error("[Workflow] 流程表初始化失败：{}", e.getMessage(), e);
        }
    }

    private void execute(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // 单条失败不阻断其余表创建（如数据库账号无 DDL 权限），日志留痕
            log.warn("[Workflow] 建表语句执行失败：{}", e.getMessage());
        }
    }
}
