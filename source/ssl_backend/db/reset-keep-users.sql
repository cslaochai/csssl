-- ============================================================
-- csssl CA 平台 —— 清业务数据、保留账号与角色授权
--
-- 与 reset.sql 的区别：**保留 3 张账号授权表**，只清业务数据。
--   保留：ssl_sys_user（用户账号）、ssl_sys_role（角色档案）、ssl_role_perm（角色授权）
--   清空：其余全部业务表
--
-- 适用场景：
--   · 想清掉历史证书/申请/公司/审计日志，但保留测试账号继续用；
--   · 重命名改造后，角色编码变更（TENANT_ADMIN → COMPANY_ADMIN）需要保留授权配置。
--
-- 执行方式：
--     mysql -h <host> -P <port> -u <user> -p <db> < reset-keep-users.sql
--
-- ⚠️ 注意：
--   1) 保留的 3 张表里，角色编码可能仍是改造前的旧值（如 TENANT_ADMIN）。
--      代码侧若已改名，需自行同步：UPDATE ssl_sys_user SET role_code='COMPANY_ADMIN'
--      WHERE role_code='TENANT_ADMIN';（ssl_sys_role / ssl_role_perm 同样处理，
--      否则角色匹配不上 → 该角色用户登录后权限为空）。
--   2) 磁盘 store-dir 仍建议清盘：旧根CA的 Shamir 份额会被"5 份齐全"判据误命中。
--   3) 旧 ssl_key_vault 密文依赖旧 master.key；若 master.key 已随清盘删除，
--      那些私钥永久解不开（属历史数据，请按需重建）。
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ---------- 一、重命名后废弃的旧表 ----------
DROP TABLE IF EXISTS `ssl_ca_tenant`;
DROP TABLE IF EXISTS `ssl_tenant_key_share`;
DROP TABLE IF EXISTS `ssl_tenant_op_ticket`;

-- ---------- 二、业务表（全部清空） ----------
DROP TABLE IF EXISTS `ssl_api_client`;
DROP TABLE IF EXISTS `ssl_application_trail`;
DROP TABLE IF EXISTS `ssl_audit_log`;
DROP TABLE IF EXISTS `ssl_ca_cert`;
DROP TABLE IF EXISTS `ssl_ca_company`;
DROP TABLE IF EXISTS `ssl_ca_subca_approval`;
DROP TABLE IF EXISTS `ssl_ca_subca_ticket`;
DROP TABLE IF EXISTS `ssl_cert_application`;
DROP TABLE IF EXISTS `ssl_cert_op_ticket`;
DROP TABLE IF EXISTS `ssl_cert_template`;
DROP TABLE IF EXISTS `ssl_company_key_share`;
DROP TABLE IF EXISTS `ssl_company_op_ticket`;
DROP TABLE IF EXISTS `ssl_crl_state`;
DROP TABLE IF EXISTS `ssl_key_vault`;
DROP TABLE IF EXISTS `ssl_message`;

-- ⚠️ 以下 3 张表**故意不删**（保留账号与角色授权）：
--    ssl_sys_user / ssl_sys_role / ssl_role_perm

SET FOREIGN_KEY_CHECKS = 1;

-- ---------- 三、自检 ----------
-- 预期：只剩 3 张账号授权表
SELECT table_name AS 'remaining_table'
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_type = 'BASE TABLE'
ORDER BY table_name;

-- 预期：N 行（账号数，>0 即用户已保留）
SELECT COUNT(*) AS 'kept_users' FROM ssl_sys_user;
