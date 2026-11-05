-- ============================================================
-- S2 迁移：二级CA / 证书 / CRL 下沉到「系统」维度
-- 幂等、可重复执行；只新增列/索引、回填 system_name、调整 CRL 状态唯一键。
--
-- 前置：migrate-s1-system.sql 已执行（ssl_ca_system 表存在且每公司至少有一个系统）。
-- 用法（本项目 sql.init 关闭，启动不会自动建表，必须手工导入）：
--   mysql -h 192.168.88.132 -u xz_feng -p ssl_ca < db/migrate-s2-system-ca.sql
--
-- 背景：二级CA 从「每公司一张」下沉为「每系统一张」（system_name = 证书主体 O）。
-- 证书(ssl_ca_cert)、申请单(ssl_cert_application)、CRL 状态(ssl_crl_state) 均按
-- (company_uscc, system_name) 定位；ROOT 行的 system_name 恒为 NULL，SUB_CA 与签发证书填所属系统。
-- 存量行（老「每公司一张 SUB_CA」模型）统一回填到「公司首个系统名」，缺口再回落 company.subject_o。
-- 后端签发/发布路径对无法判定 systemName 的入口一律用「公司首个系统名」兜底，无编译/运行期 TODO。
-- ============================================================

-- ---------- 1) 幂等加列（schema.sql 已含，这里再兜底一遍） ----------
SET @c_s1 = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND column_name = 'system_name');
SET @s_s1 = IF(@c_s1 = 0, 'ALTER TABLE ssl_ca_cert ADD COLUMN system_name VARCHAR(128) NULL COMMENT ''系统维度（证书主体O）；ROOT行为NULL，SUB_CA与签发证书填所属系统'' AFTER company_uscc', 'SELECT 1');
PREPARE s_s1 FROM @s_s1; EXECUTE s_s1; DEALLOCATE PREPARE s_s1;

SET @c_s2 = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND column_name = 'system_name');
SET @s_s2 = IF(@c_s2 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN system_name VARCHAR(128) NULL COMMENT ''系统维度（证书主体O）；缺省回落公司首个系统'' AFTER company_uscc', 'SELECT 1');
PREPARE s_s2 FROM @s_s2; EXECUTE s_s2; DEALLOCATE PREPARE s_s2;

SET @c_s3 = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND column_name = 'system_name');
SET @s_s3 = IF(@c_s3 = 0, 'ALTER TABLE ssl_crl_state ADD COLUMN system_name VARCHAR(128) NULL COMMENT ''系统维度（证书主体O）；CRL 按(company_uscc,system_name)独立发布'' AFTER company_uscc', 'SELECT 1');
PREPARE s_s3 FROM @s_s3; EXECUTE s_s3; DEALLOCATE PREPARE s_s3;

-- ---------- 2) 回填 system_name ----------
-- 规则：优先取公司首个系统（ssl_ca_system.id 最小），否则回落到公司主体 O（subject_o）。
-- 注：ROOT 类型为 NULL，无需回填；这里对全部行回填（ROOT 也落到首个系统名，但后端查询 ROOT 不按 system_name 过滤，无副作用）。

-- 2.1 ssl_ca_cert（ROOT 行为 NULL，不回填；仅 SUB_CA / 签发证书落到所属系统）
UPDATE ssl_ca_cert c
JOIN (
    SELECT company_uscc, MIN(system_name) AS sys
    FROM ssl_ca_system
    GROUP BY company_uscc
) s ON s.company_uscc = c.company_uscc
SET c.system_name = s.sys
WHERE c.system_name IS NULL AND c.cert_type <> 'ROOT';
UPDATE ssl_ca_cert c
JOIN ssl_ca_company co ON co.uscc = c.company_uscc
SET c.system_name = co.subject_o
WHERE c.system_name IS NULL AND c.cert_type <> 'ROOT' AND co.subject_o IS NOT NULL AND co.subject_o <> '';

-- 2.2 ssl_cert_application
UPDATE ssl_cert_application a
JOIN (
    SELECT company_uscc, MIN(system_name) AS sys
    FROM ssl_ca_system
    GROUP BY company_uscc
) s ON s.company_uscc = a.company_uscc
SET a.system_name = s.sys
WHERE a.system_name IS NULL;
UPDATE ssl_cert_application a
JOIN ssl_ca_company co ON co.uscc = a.company_uscc
SET a.system_name = co.subject_o
WHERE a.system_name IS NULL AND co.subject_o IS NOT NULL AND co.subject_o <> '';

-- 2.3 ssl_crl_state（存量每公司一行，统一回落到首个系统名）
UPDATE ssl_crl_state st
JOIN (
    SELECT company_uscc, MIN(system_name) AS sys
    FROM ssl_ca_system
    GROUP BY company_uscc
) s ON s.company_uscc = st.company_uscc
SET st.system_name = s.sys
WHERE st.system_name IS NULL;
UPDATE ssl_crl_state st
JOIN ssl_ca_company co ON co.uscc = st.company_uscc
SET st.system_name = co.subject_o
WHERE st.system_name IS NULL AND co.subject_o IS NOT NULL AND co.subject_o <> '';

-- ---------- 3) CRL 状态唯一键：(company_uscc) -> (company_uscc, system_name) ----------
-- 先删除旧唯一键（若存在），再补建新唯一键（含 system_name）。
SET @has_old = (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND index_name = 'uk_crl_state_company');
SET @drop_old = IF(@has_old > 0, 'ALTER TABLE ssl_crl_state DROP INDEX uk_crl_state_company', 'SELECT 1');
PREPARE stmt_drop FROM @drop_old; EXECUTE stmt_drop; DEALLOCATE PREPARE stmt_drop;

SET @has_new = (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND index_name = 'uk_crl_state_company_system');
SET @add_new = IF(@has_new = 0, 'ALTER TABLE ssl_crl_state ADD UNIQUE KEY uk_crl_state_company_system (company_uscc, system_name)', 'SELECT 1');
PREPARE stmt_add FROM @add_new; EXECUTE stmt_add; DEALLOCATE PREPARE stmt_add;

-- ---------- 4) 辅助索引：按 (company_uscc, system_name) 查询加速 ----------
SET @c_i1 = (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND index_name = 'idx_cert_system');
SET @s_i1 = IF(@c_i1 = 0, 'ALTER TABLE ssl_ca_cert ADD KEY idx_cert_system (company_uscc, system_name)', 'SELECT 1');
PREPARE stmt_i1 FROM @s_i1; EXECUTE stmt_i1; DEALLOCATE PREPARE stmt_i1;

SET @c_i2 = (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND index_name = 'idx_app_system');
SET @s_i2 = IF(@c_i2 = 0, 'ALTER TABLE ssl_cert_application ADD KEY idx_app_system (company_uscc, system_name)', 'SELECT 1');
PREPARE stmt_i2 FROM @s_i2; EXECUTE stmt_i2; DEALLOCATE PREPARE stmt_i2;

-- ---------- 5) 校验 ----------
SELECT '=== ssl_ca_cert（system_name 为 NULL 的应为 0 行，ROOT 除外则允许） ===' AS section;
SELECT COUNT(*) AS cert_null_system
FROM ssl_ca_cert
WHERE system_name IS NULL AND cert_type <> 'ROOT';

SELECT '=== ssl_ca_cert ROOT 行的 system_name 应为 NULL（系统无关） ===' AS section;
SELECT COUNT(*) AS root_with_system
FROM ssl_ca_cert
WHERE cert_type = 'ROOT' AND system_name IS NOT NULL;

SELECT '=== ssl_cert_application（system_name 为 NULL 的应为 0 行） ===' AS section;
SELECT COUNT(*) AS app_null_system
FROM ssl_cert_application
WHERE system_name IS NULL;

SELECT '=== ssl_crl_state（每 company+system 唯一，system_name 不应为空） ===' AS section;
SELECT id, company_uscc, system_name, crl_number FROM ssl_crl_state ORDER BY id;

SELECT '=== ssl_ca_system（S1 已建，复核） ===' AS section;
SELECT id, company_uscc, system_name, status FROM ssl_ca_system ORDER BY id;
