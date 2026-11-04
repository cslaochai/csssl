-- ============================================================
-- csssl CA 平台 —— 演示/初始化账号数据
--
-- 数据来源：front/src/views/login/LoginView.vue 的「演示账号凭据」清单
--          （注释记录：2026-11-01 直读 ssl_ca.ssl_sys_user，11 个全局账号）
--          + db/init-key-admins.sql 的 5 名密钥管理员（本脚本不重复插入）
--
-- 口令：统一 123456（BCrypt 存储；哈希与 db/init-key-admins.sql 同源）
--       ⚠️ 生产环境请勿使用本脚本，或务必改掉口令。
--
-- 执行方式：
--     mysql -h <host> -P <port> -u <user> -p <db> < init-demo-users.sql
--
-- 幂等：账号已存在（按 username 判重）则跳过，不会覆盖既有账号与口令。
-- 角色档案由 DataInitializer 启动时按 RoleCodeEnum 幂等播种，本脚本只写用户。
-- ============================================================

-- ---------- 管理/业务角色账号（登录页演示清单）----------

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'zhangshuhao', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '张书豪', 'COMPANY_ADMIN', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'zhangshuhao');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'luoyiming', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '罗一鸣', 'COMPANY_APPROVER', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'luoyiming');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'liuyaqin', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '刘雅琴', 'USER_ADMIN', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'liuyaqin');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'wanghao', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '王浩', 'AUDITOR', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'wanghao');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'zhouziang', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '周子昂', 'AUDITOR', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'zhouziang');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'songqingran', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '宋清然', 'OPERATOR', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'songqingran');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'zhaoqiming', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '赵启明', 'OPERATOR', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'zhaoqiming');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'liwenbo', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '李文博', 'OPERATOR', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'liwenbo');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'suwenwen', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '苏雯雯', 'APPROVER', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'suwenwen');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'hejing', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '何静', 'APPROVER', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'hejing');

-- ---------- 流程节点办理人所需的角色账号 ----------
-- WorkflowSeeder 的四条流程引用了 role:CERT_OFFICER / role:SECURITY_ADMIN，
-- 库里缺这两类账号时，流程启动/推进到对应节点会报「权限标识未匹配到任何办理人」。

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'certofficer1', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '证书签发员', 'CERT_OFFICER', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'certofficer1');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, created_by_name)
SELECT 'securityadmin1', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '安全管理员', 'SECURITY_ADMIN', 1, 0, 0, NOW(), '系统'
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'securityadmin1');

-- ---------- 自检 ----------
-- 预期：admin + 5 名密钥管理员 + 以上 12 个账号（无 RA_ADMIN）
SELECT username, real_name, role_code, status
FROM ssl_sys_user ORDER BY role_code, username;
