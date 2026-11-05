-- ============================================================
-- S1 迁移：公司下的「系统」表 + 存量系统回填
-- 幂等、可重复执行；只新增表/行，不删任何历史数据。
--
-- 用法（本项目 sql.init 关闭，启动不会自动建表，必须手工导入）：
--   mysql -h 192.168.88.132 -u xz_feng -p ssl_ca < db/migrate-s1-system.sql
--
-- 背景：系统（system_name，即证书主体 O）才是证书域单元，公司只是客户壳。
-- 根 CA、门限份额、master.key、接入凭证 vault 一律不动，仅补「系统」这一层。
-- ============================================================

CREATE TABLE IF NOT EXISTS ssl_ca_system (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    company_uscc       VARCHAR(32)  NOT NULL COMMENT '所属公司（统一社会信用代码）',
    system_name        VARCHAR(128) NOT NULL COMMENT '系统名称（= 证书主体 O；公司内唯一，禁止路径分隔符）',
    status             TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    description        VARCHAR(500) NULL COMMENT '说明',
    created_by_user_id BIGINT       NULL COMMENT '创建人ID',
    created_by_name    VARCHAR(64)  NULL COMMENT '创建人真实姓名（写入时快照）',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_company_name (company_uscc, system_name),
    KEY idx_system_company (company_uscc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公司下的系统（证书域单元，每系统一张专属二级CA）';

-- 回填 1：现有接入客户端已经带了 system_name，按它建立对应系统（保留历史凭证不动）
INSERT INTO ssl_ca_system (company_uscc, system_name, status, description, created_at)
SELECT DISTINCT ac.company_uscc, ac.system_name, 1, '存量接入客户端自动补建的系统', NOW()
FROM ssl_api_client ac
WHERE ac.system_name IS NOT NULL AND ac.system_name <> ''
ON DUPLICATE KEY UPDATE status = status;

-- 回填 2：公司表里还没有任何系统的，用主体 O 补建首个系统（存量公司兼容）
INSERT INTO ssl_ca_system (company_uscc, system_name, status, description, created_at)
SELECT co.uscc, co.subject_o, 1, '存量公司按主体O补建的首个系统', NOW()
FROM ssl_ca_company co
WHERE co.subject_o IS NOT NULL AND co.subject_o <> ''
  AND NOT EXISTS (SELECT 1 FROM ssl_ca_system sy WHERE sy.company_uscc = co.uscc)
ON DUPLICATE KEY UPDATE status = status;

-- 校验：期望每个有接入客户端的公司，其 system_name 都能在 ssl_ca_system 找到
SELECT '=== ssl_ca_system ===' AS section;
SELECT id, company_uscc, system_name, status, description FROM ssl_ca_system ORDER BY id;
SELECT '=== ssl_api_client（历史保持不变） ===' AS section;
SELECT id, app_id, company_uscc, client_name, system_name, status FROM ssl_api_client ORDER BY id;
SELECT '=== 未归属系统的接入客户端（应为 0 行） ===' AS section;
SELECT ac.app_id, ac.company_uscc, ac.system_name
FROM ssl_api_client ac
WHERE ac.system_name IS NOT NULL AND ac.system_name <> ''
  AND NOT EXISTS (SELECT 1 FROM ssl_ca_system sy
                  WHERE sy.company_uscc = ac.company_uscc AND sy.system_name = ac.system_name);

-- 说明：ssl_api_client.system_code 列已废弃（系统统一以 system_name 为业务键），
-- 保留该列仅为兼容存量数据，代码不再读写，不做 DROP COLUMN 以免影响历史行。
-- ssl_ca_cert.system_name 等列的扩展属于 S2 阶段，不在本迁移内。
