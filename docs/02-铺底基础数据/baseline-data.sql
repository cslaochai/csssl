-- ============================================================
-- csssl 清库脚本（清空业务表，保留铺底账号/角色/权限/模板/流程定义）
-- 适用：开发环境清库重建时执行，清完后重启后端自动初始化根CA + SUB_CA + 份额。
-- 不灌任何业务数据（公司、证书、密钥库、API客户端等全部清空，由运行时自动重建）。
-- ============================================================
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;

-- 清空业务表（按外键反序）
TRUNCATE TABLE `ssl_message`;
TRUNCATE TABLE `ssl_audit_log`;
TRUNCATE TABLE `ssl_application_trail`;
TRUNCATE TABLE `ssl_cert_application`;
TRUNCATE TABLE `ssl_cert_op_ticket`;
TRUNCATE TABLE `ssl_company_op_ticket`;
TRUNCATE TABLE `ssl_company_op_vote`;
TRUNCATE TABLE `ssl_ca_subca_ticket`;
TRUNCATE TABLE `ssl_ca_subca_approval`;
TRUNCATE TABLE `flow_his_task`;
TRUNCATE TABLE `flow_task`;
TRUNCATE TABLE `flow_instance`;
TRUNCATE TABLE `flow_user`;
TRUNCATE TABLE `ssl_api_client`;
TRUNCATE TABLE `ssl_ca_system`;
TRUNCATE TABLE `ssl_company_key_share`;
TRUNCATE TABLE `ssl_key_vault`;
TRUNCATE TABLE `ssl_crl_state`;
TRUNCATE TABLE `ssl_ca_cert`;
TRUNCATE TABLE `ssl_ca_company`;
-- 只保留平台级系统账号（admin / keyman1~5 / securityadmin1），业务用户全清
DELETE FROM `ssl_sys_user` WHERE id NOT IN (1,2,3,4,5,2117495840921632769,2117500000000000015);

SET FOREIGN_KEY_CHECKS=1;
