-- ============================================================
-- csssl CA 平台 —— 清库脚本（drop 全部业务表）
--
-- 用途：历史数据清零后重建。执行本脚本后，库内所有业务表被删除，
--       随后重新导入 schema.sql 即可得到干净的新库。
--
-- 执行方式（命令行）：
--     mysql -h <host> -P <port> -u <user> -p <db> < reset.sql
--   或在 Navicat / MySQL Workbench 里打开本文件执行。
--
-- ⚠️ 重要：本脚本会 DELETE 全部业务数据（证书、申请、公司、用户、
--    角色授权、审计日志、密钥保险库…），且不可恢复。
--    执行前请确认已备份需要留存的库。
--
-- ⚠️ 配套动作（必须一起做，否则重建后会踩"陈旧份额"坑）：
--    删表后请一并清掉磁盘密钥目录 store-dir（默认 ./data/certs），
--    否则旧根CA的 Shamir 份额文件会被"5 份齐全"判据误命中，
--    导致新公司拿到旧根私钥的份额、后续解封签名挑战必然失败。
--    清盘命令见 reset-dev-db.sh 或 README 说明。
--
-- 💡 想保留用户账号但清掉业务数据？用 reset-keep-users.sql
--    （保留 ssl_sys_user / ssl_sys_role / ssl_role_perm 三张账号授权表）。
--
-- 说明：
--   * 全库 0 个外键约束，表间无依赖顺序要求，故按字母序 DROP 即可。
--   * 视图 / 存储过程 / 触发器 / EVENT 本项目未使用，无需清理。
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 一、本次重命名后废弃的旧表（历史残留，新库不再使用） ----------
DROP TABLE IF EXISTS `ssl_ca_tenant`;            -- 旧：公司注册表（已改名 ssl_ca_company）
DROP TABLE IF EXISTS `ssl_tenant_key_share`;     -- 旧：公司密钥份额保管人绑定（已改名 ssl_company_key_share）
DROP TABLE IF EXISTS `ssl_tenant_op_ticket`;     -- 旧：公司生命周期变更单（已改名 ssl_company_op_ticket）

-- ---------- 二、当前全部业务表 ----------
DROP TABLE IF EXISTS `ssl_api_client`;           -- 外部系统 API 客户端（HIS 等）
DROP TABLE IF EXISTS `ssl_application_trail`;     -- 证书申请单流转轨迹
DROP TABLE IF EXISTS `ssl_audit_log`;            -- CA 操作审计日志
DROP TABLE IF EXISTS `ssl_ca_cert`;              -- 证书总账（根CA + 签发证书）
DROP TABLE IF EXISTS `ssl_ca_company`;           -- 公司注册表（每公司独立根CA域）
DROP TABLE IF EXISTS `ssl_ca_subca_approval`;    -- 二级CA重建审批记录（逐人投票）
DROP TABLE IF EXISTS `ssl_ca_subca_ticket`;      -- 二级CA重建申请单
DROP TABLE IF EXISTS `ssl_cert_application`;     -- 证书申请单
DROP TABLE IF EXISTS `ssl_cert_op_ticket`;       -- 证书操作变更单（暂停/吊销/更新）
DROP TABLE IF EXISTS `ssl_cert_template`;        -- 证书模板
DROP TABLE IF EXISTS `ssl_company_key_share`;    -- 公司根私钥门限份额保管人绑定
DROP TABLE IF EXISTS `ssl_company_op_ticket`;    -- 公司生命周期变更单
DROP TABLE IF EXISTS `ssl_crl_state`;            -- CRL 状态
DROP TABLE IF EXISTS `ssl_key_vault`;            -- 密钥保险库（私钥密文）
DROP TABLE IF EXISTS `ssl_message`;              -- 站内信/消息
DROP TABLE IF EXISTS `ssl_role_perm`;            -- 角色-权限点授权
DROP TABLE IF EXISTS `ssl_sys_role`;             -- 角色档案
DROP TABLE IF EXISTS `ssl_sys_user`;             -- 平台用户

SET FOREIGN_KEY_CHECKS = 1;

-- ---------- 四、自检：确认已无残留业务表 ----------
-- 预期结果：0 行（若不为 0，说明还有未列入本脚本的表需手工处理）
SELECT table_name AS 'remaining_table'
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_type = 'BASE TABLE';
