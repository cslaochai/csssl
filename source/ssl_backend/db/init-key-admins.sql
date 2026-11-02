-- ============================================================
-- csssl CA 平台 —— 初始化测试用密钥管理员账号（5 名）
--
-- 用途：新建公司时「密钥保管人(5人)」下拉需要 5 个 KEY_ADMIN 账号，
--       否则下拉为空、无法建公司。清库后（或首次部署）执行本脚本补齐。
--
-- 角色说明：保管人限定为 KEY_ADMIN（密钥管理员），且建公司时该公司账号
--           尚不存在 → 只能指定**平台级**账号（company_uscc = NULL）。
--           公司建好后可在「公司详情 → 重新指定保管人」改指为公司自有账号。
--
-- 口令：统一 123456（BCrypt 存储，$2a$10$ 前缀 = hutool BCrypt）。
--       ⚠️ 生产环境请勿使用本脚本，或务必改掉口令。
--
-- 执行方式：
--     mysql -h <host> -P <port> -u <user> -p <db> < init-key-admins.sql
--
-- 幂等：账号已存在则跳过（INSERT ... SELECT WHERE NOT EXISTS）。
-- ============================================================

-- ---------- 1) 确保 KEY_ADMIN 角色档案存在 ----------
INSERT INTO ssl_sys_role (role_code, role_name, role_desc, sort_order, status, builtin, created_at, updated_at)
SELECT 'KEY_ADMIN', '密钥管理员', '根密钥门限分持/封存/解封与份额保管', 60, 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_role WHERE role_code = 'KEY_ADMIN');

-- ---------- 2) 建 5 名平台级密钥管理员 ----------
-- 口令哈希 = BCrypt("123456", gensalt) —— 与现有测试账号同源，登录走同一套校验
INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, company_uscc)
SELECT 'keyman1', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '密钥保管人甲', 'KEY_ADMIN', 1, 0, 0, NOW(), NULL
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'keyman1');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, company_uscc)
SELECT 'keyman2', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '密钥保管人乙', 'KEY_ADMIN', 1, 0, 0, NOW(), NULL
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'keyman2');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, company_uscc)
SELECT 'keyman3', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '密钥保管人丙', 'KEY_ADMIN', 1, 0, 0, NOW(), NULL
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'keyman3');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, company_uscc)
SELECT 'keyman4', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '密钥保管人丁', 'KEY_ADMIN', 1, 0, 0, NOW(), NULL
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'keyman4');

INSERT INTO ssl_sys_user (username, password, real_name, role_code, status, failed_attempts, token_version, created_at, company_uscc)
SELECT 'keyman5', '$2a$10$UnxLvi4PXVz9kXu/onfjremQOjyZg7a394BIZxkTRLAlDt5JObSPe', '密钥保管人戊', 'KEY_ADMIN', 1, 0, 0, NOW(), NULL
WHERE NOT EXISTS (SELECT 1 FROM ssl_sys_user WHERE username = 'keyman5');

-- ---------- 3) 赋权：KEY_ADMIN 法定上限（对齐 RoleCodeEnum.defaultMax）----------
INSERT IGNORE INTO ssl_role_perm (role_code, perm_code) VALUES
    ('KEY_ADMIN', 'menu:dashboard'),          ('KEY_ADMIN', 'menu:certs'),
    ('KEY_ADMIN', 'menu:root-ca'),            ('KEY_ADMIN', 'menu:crl'),
    ('KEY_ADMIN', 'menu:ocsp'),               ('KEY_ADMIN', 'menu:tsa'),
    ('KEY_ADMIN', 'cert:list'),               ('KEY_ADMIN', 'cert:key:download'),
    ('KEY_ADMIN', 'cert:export'),             ('KEY_ADMIN', 'ca:init'),
    ('KEY_ADMIN', 'ca:seal'),                 ('KEY_ADMIN', 'ca:unseal'),
    ('KEY_ADMIN', 'ca:share:view'),           ('KEY_ADMIN', 'ca:share:download'),
    ('KEY_ADMIN', 'ca:share:migrate'),        ('KEY_ADMIN', 'ca:share:unseal'),
    ('KEY_ADMIN', 'ca:root:download'),        ('KEY_ADMIN', 'ca:subca:download'),
    ('KEY_ADMIN', 'ca:share:assign'),         ('KEY_ADMIN', 'ca:subca:rebuild:execute'),
    ('KEY_ADMIN', 'crl:download');

-- ---------- 4) 自检 ----------
-- 预期：5 行
SELECT username, real_name, role_code, status, company_uscc
FROM ssl_sys_user WHERE role_code = 'KEY_ADMIN' ORDER BY username;
