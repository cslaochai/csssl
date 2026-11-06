package com.csssl.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关：CRL 每日判重发布（CrlService#dailyRefresh）、SUB_CA 到期预检轮换（CaService#dailyPrecheck）。
 * cron 均为 Spring 占位符直读环境（csssl.crl-refresh-cron / csssl.subca-precheck-cron），不入业务配置类。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
