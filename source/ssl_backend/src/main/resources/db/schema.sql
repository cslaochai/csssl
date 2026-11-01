-- =============================================================================
-- CA根认证中心（csssl）数据库脚本，库：ssl_ca
-- 与 csmig（mig_gateway）/ cslk HIS 风格一致：utf8mb4、InnoDB、中文注释
-- =============================================================================

-- 管理用户：登录鉴权（对齐 cslk sys_user / csmig mig_sys_user 核心字段；初始 admin 账号由 DataInitializer 创建）
CREATE TABLE IF NOT EXISTS ssl_sys_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    username         VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password         VARCHAR(128) NOT NULL COMMENT 'BCrypt密码哈希',
    real_name        VARCHAR(64)  NOT NULL COMMENT '姓名',
    role_code        VARCHAR(32)  NOT NULL DEFAULT 'ADMIN' COMMENT '角色编码',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',
    last_login_time  DATETIME     NULL COMMENT '最近登录时间',
    last_login_ip    VARCHAR(64)  NULL COMMENT '最近登录IP',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CA认证中心管理用户';

-- 证书总账：根CA与所有签发证书（含吊销信息）。私钥只落盘（store-dir），不进本表
CREATE TABLE IF NOT EXISTS ssl_ca_cert (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    serial_hex     VARCHAR(64)  NOT NULL COMMENT '证书序列号（16进制，唯一）',
    subject_cn     VARCHAR(200) NOT NULL COMMENT '主体CN（颁发给谁）',
    cert_type      VARCHAR(16)  NOT NULL COMMENT '证书类型：ROOT-根CA SUB_CA-二级CA SERVER-服务端 CLIENT-客户端',
    algorithm      VARCHAR(32)  NOT NULL COMMENT '算法：RSA2048 / SM2',
    issuer_cn      VARCHAR(200) NULL COMMENT '签发者CN（根CA为自身CN）',
    san            VARCHAR(500) NULL COMMENT 'SAN列表（DNS/IP，逗号分隔）',
    valid_from     DATETIME     NOT NULL COMMENT '生效时间',
    valid_to       DATETIME     NOT NULL COMMENT '过期时间',
    status         VARCHAR(16)  NOT NULL DEFAULT 'VALID' COMMENT '状态：VALID-有效 REVOKED-已吊销 EXPIRED-已过期',
    revoke_reason  VARCHAR(200) NULL COMMENT '吊销原因',
    revoked_at     DATETIME     NULL COMMENT '吊销时间',
    cert_pem       MEDIUMTEXT   NOT NULL COMMENT '证书PEM（公钥证书，可入库）',
    description    VARCHAR(500) NULL COMMENT '用途说明（颁发给哪个子系统/服务）',
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'SYSTEM' COMMENT '创建人（管理账号）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_serial (serial_hex),
    KEY idx_type_status (cert_type, status),
    KEY idx_subject (subject_cn),
    KEY idx_valid_to (valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CA证书总账（根CA+签发证书）';

-- 操作审计日志：登录/签发/吊销/导出等管理操作留痕，等保审计用
CREATE TABLE IF NOT EXISTS ssl_audit_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL COMMENT '操作人',
    action     VARCHAR(32)  NOT NULL COMMENT '操作：LOGIN/LOGOUT/CA_INIT/CERT_ISSUE/CERT_CSR_ISSUE/CERT_REVOKE/CERT_DOWNLOAD/CRL_DOWNLOAD',
    target     VARCHAR(128) NULL COMMENT '操作对象（证书ID/序列号等）',
    detail     VARCHAR(500) NULL COMMENT '操作详情',
    ip         VARCHAR(64)  NULL COMMENT '操作来源IP',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '操作时间（微秒精度，审计签名 canonical 含 .SSSSSS，须与列精度严格一致防舍入偏差）',
    PRIMARY KEY (id),
    KEY idx_action_time (action, created_at),
    KEY idx_username_time (username, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CA管理操作审计日志';

-- ===== GM/T 0034 P0 合规扩展 =====

-- 证书模板：固化类型/算法/有效期/KeyUsage/EKU/策略OID（GM/T 0034 §5.3.4 证书策略管理）
CREATE TABLE IF NOT EXISTS ssl_cert_template (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    template_code  VARCHAR(32)  NOT NULL COMMENT '模板编码：SERVER_COMM-租户通信/DOCTOR_SIGN-医生签名/SUB_CA-二级CA',
    template_name  VARCHAR(64)  NOT NULL COMMENT '模板名称',
    cert_type      VARCHAR(16)  NOT NULL COMMENT '证书类型：SERVER/CLIENT/SUB_CA',
    algorithm      VARCHAR(32)  NOT NULL DEFAULT 'SM2' COMMENT '算法：RSA2048 / SM2',
    validity_days  INT          NOT NULL DEFAULT 365 COMMENT '默认有效期（天）',
    key_usage      VARCHAR(64)  NOT NULL COMMENT 'KeyUsage：digitalSignature,keyEncipherment,keyCertSign,cRLSign',
    ext_key_usage  VARCHAR(64)  NULL COMMENT 'EKU：serverAuth/clientAuth（SUB_CA 为空）',
    policy_oid     VARCHAR(64)  NULL COMMENT '证书策略OID（GM/T 0015 证书格式扩展）',
    builtin        TINYINT      NOT NULL DEFAULT 0 COMMENT '1-内置模板(不可删除) 0-自定义',
    description    VARCHAR(500) NULL COMMENT '模板说明',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证书模板（GM/T 0034 §5.3.4）';

-- 证书申请单（RA 流程：提交→审核→签发，GM/T 0034 §5.2.2 用户注册管理系统）
CREATE TABLE IF NOT EXISTS ssl_cert_application (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    apply_no        VARCHAR(32)  NOT NULL COMMENT '申请单号',
    applicant_type  VARCHAR(16)  NOT NULL COMMENT '申请人类别：INSTITUTION-租户 PERSON-个人',
    applicant_name  VARCHAR(64)  NOT NULL COMMENT '申请人/租户名称',
    subject_dn      VARCHAR(255) NOT NULL COMMENT '证书主体DN（RFC2253，如 CN=张三,O=医院；只放姓名/租户等稳定身份，科室等易变属性不进DN）',
    template_id     BIGINT       NOT NULL COMMENT '证书模板ID',
    san             VARCHAR(500) NULL COMMENT 'SAN列表（DNS/IP，逗号分隔）',
    public_key_pem  MEDIUMTEXT   NULL COMMENT '申请方公钥PEM（自持私钥场景，可为空则由CA在线生成）',
    description     VARCHAR(500) NULL COMMENT '用途说明',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待审核 APPROVED-已批准 REJECTED-已驳回 ISSUED-已签发 CANCELLED-已撤销',
    applied_by      VARCHAR(64)  NOT NULL COMMENT '提交人（录入员，与审核人不得同一人）',
    applied_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    reviewed_by     VARCHAR(64)  NULL COMMENT '审核人',
    reviewed_at     DATETIME     NULL COMMENT '审核时间',
    review_comment  VARCHAR(500) NULL COMMENT '审核意见',
    issued_cert_id  BIGINT       NULL COMMENT '签发证书ID',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_apply_no (apply_no),
    KEY idx_status (status),
    KEY idx_applicant (applicant_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证书申请单（RA 提交-审核-签发）';

-- 幂等加列：审计日志防篡改签名（GM/T 0034 §10.1/§10.5 审计记录数字签名）
SET @c1 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND column_name = 'sign');
SET @s1 = IF(@c1 = 0, 'ALTER TABLE ssl_audit_log ADD COLUMN sign VARCHAR(256) NULL COMMENT ''审计签名（SM3withSM2 hex，防篡改）'' AFTER ip', 'SELECT 1');
PREPARE s1 FROM @s1; EXECUTE s1; DEALLOCATE PREPARE s1;

-- 幂等加列：证书签发绑定模板
SET @c2 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND column_name = 'template_id');
SET @s2 = IF(@c2 = 0, 'ALTER TABLE ssl_ca_cert ADD COLUMN template_id BIGINT NULL COMMENT ''签发所用证书模板ID'' AFTER description', 'SELECT 1');
PREPARE s2 FROM @s2; EXECUTE s2; DEALLOCATE PREPARE s2;

-- ===== GM/T 0034 P1 合规增强 =====

-- 幂等加列：审计日志哈希链（上一条记录的签名值，链式防篡改增强，行业实践）
SET @c3 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND column_name = 'prev_sign');
SET @s3 = IF(@c3 = 0, 'ALTER TABLE ssl_audit_log ADD COLUMN prev_sign VARCHAR(256) NULL COMMENT ''哈希链：上一条审计记录的签名值'' AFTER sign', 'SELECT 1');
PREPARE s3 FROM @s3; EXECUTE s3; DEALLOCATE PREPARE s3;

-- 幂等升级：审计时间列微秒精度（canonical 含 .SSSSSS 与 DATETIME(6) 严格对齐；
-- 早期 DATETIME 秒精度截断毫秒、DATETIME(3) 对微秒四舍五入都会导致历史记录验签失败）
SET @c4 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND column_name = 'created_at'
             AND datetime_precision < 6);
SET @s4 = IF(@c4 > 0, 'ALTER TABLE ssl_audit_log MODIFY created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)',
             'SELECT 1');
PREPARE s4 FROM @s4; EXECUTE s4; DEALLOCATE PREPARE s4;

-- ===== 路线 B：安全缺陷整改扩展 =====

-- 幂等加列：登录失败锁定（暴力口令防护，等保身份鉴别要求）
SET @c5 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_sys_user' AND column_name = 'failed_attempts');
SET @s5 = IF(@c5 = 0, 'ALTER TABLE ssl_sys_user ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0 COMMENT ''连续登录失败次数（成功后清零）'' AFTER last_login_ip', 'SELECT 1');
PREPARE s5 FROM @s5; EXECUTE s5; DEALLOCATE PREPARE s5;

SET @c6 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_sys_user' AND column_name = 'locked_until');
SET @s6 = IF(@c6 = 0, 'ALTER TABLE ssl_sys_user ADD COLUMN locked_until DATETIME NULL COMMENT ''锁定截止时间（NULL=未锁定）'' AFTER failed_attempts', 'SELECT 1');
PREPARE s6 FROM @s6; EXECUTE s6; DEALLOCATE PREPARE s6;

-- 幂等加列：token 版本号（改密/改角色/停用即递增，JWT 载荷带版本，旧 token 即刻失效）
SET @c7 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_sys_user' AND column_name = 'token_version');
SET @s7 = IF(@c7 = 0, 'ALTER TABLE ssl_sys_user ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT ''会话版本号：口令/角色/状态变更时+1，签发JWT携带，拦截器比对不符即401'' AFTER locked_until', 'SELECT 1');
PREPARE s7 FROM @s7; EXECUTE s7; DEALLOCATE PREPARE s7;

-- 幂等加列：审计哈希链前驱记录ID（prev_sign 只有签名字符串时删中间记录不可检出；
-- 对账接口按 id 序核验 prev_id 指向记录的存在性与签名衔接，空洞/分叉即告警）
SET @c8 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND column_name = 'prev_id');
SET @s8 = IF(@c8 = 0, 'ALTER TABLE ssl_audit_log ADD COLUMN prev_id BIGINT NULL COMMENT ''哈希链前驱记录ID（首条为NULL）'' AFTER target', 'SELECT 1');
PREPARE s8 FROM @s8; EXECUTE s8; DEALLOCATE PREPARE s8;

-- 幂等加列：申请单策略快照（批准时固化模板的算法/有效期/KeyUsage/EKU/策略OID，
-- 签发按快照执行——防止批准与签发之间模板被改/删导致审批结论与实际证书脱钩）
SET @c9 = (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND column_name = 'template_snapshot');
SET @s9 = IF(@c9 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN template_snapshot TEXT NULL COMMENT ''批准时固化的证书模板策略JSON（算法/有效期/KeyUsage/EKU/策略OID），签发按快照执行'' AFTER template_id', 'SELECT 1');
PREPARE s9 FROM @s9; EXECUTE s9; DEALLOCATE PREPARE s9;

-- CRL 发布状态表：取代 store-dir/crl-state.json（文件读-改-写仅进程内加锁，
-- 多实例共享 store-dir 会后写覆盖导致 CRLNumber 回退/撞号；DB 行锁保证单调）
CREATE TABLE IF NOT EXISTS ssl_crl_state (
    id                BIGINT      NOT NULL DEFAULT 1 COMMENT '固定单行(id=1)：全库唯一CRL发布状态',
    crl_number        BIGINT      NOT NULL DEFAULT 0 COMMENT '当前CRL编号（单调递增，仅内容变化时递增）',
    fingerprint       VARCHAR(64) NULL COMMENT '上次发布CRL内容指纹（吊销集合SHA-256，判重：集合未变则复用编号不递增）',
    this_update       DATETIME(6) NULL COMMENT '上次发布时间',
    next_update       DATETIME(6) NULL COMMENT '下次强制更新时间',
    delta_base_number BIGINT      NULL COMMENT '增量CRL指向的基础全量编号',
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CRL发布状态（多实例一致性，取代crl-state.json）';

-- =============================================================================
-- ===== 路线 A：多租户（按租户独立根CA） =====
-- 兼容铁律：存量单根原样成为 default 租户（subject_o='CSSSL'），
-- 其落盘路径沿用现有根路径；新租户路径 store-dir/tenants/{tenantUscc}/…，互相不信任。
-- =============================================================================

-- 租户注册表：每家医院=一套独立根CA域（根CA+SUB_CA+签发目录+主密钥+门限份额）
-- 统一社会信用代码即租户标识（路径/接口/落盘目录同名），不另设内部标识列
CREATE TABLE IF NOT EXISTS ssl_ca_tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    uscc        VARCHAR(18)  NOT NULL COMMENT '统一社会信用代码（GB 32100）：租户唯一标识 + 登记唯一键',
    tenant_name    VARCHAR(64)  NOT NULL COMMENT '租户名称（展示与新根CA CN用）',
    subject_o   VARCHAR(128) NOT NULL COMMENT '证书主体O（租户受控，根/子CA DN 的 O 字段，全局唯一）',
    algorithm   VARCHAR(32)  NOT NULL DEFAULT 'SM2' COMMENT '根CA算法：RSA2048 / SM2（建租户时定，SUB_CA 跟随根）',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用 SUSPENDED-暂停 CLOSED-关闭',
    contact       VARCHAR(64)  NULL COMMENT '联系人姓名',
    contact_phone VARCHAR(32)  NULL COMMENT '联系人手机号（前端展示做掩码）',
    description   VARCHAR(500) NULL COMMENT '备注',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_uscc (uscc),
    UNIQUE KEY uk_subject_o (subject_o)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CA租户注册表（多根CA域隔离）';

-- 幂等加列：租户联系人手机号（列表/详情展示做掩码，存量行留空）
SET @c_tel = (SELECT COUNT(*) FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_tenant' AND column_name = 'contact_phone');
SET @s_tel = IF(@c_tel = 0, 'ALTER TABLE ssl_ca_tenant ADD COLUMN contact_phone VARCHAR(32) NULL COMMENT ''联系人手机号（前端展示做掩码）'' AFTER contact', 'SELECT 1');
PREPARE s_tel FROM @s_tel; EXECUTE s_tel; DEALLOCATE PREPARE s_tel;

-- 存量库升级：租户标识并入统一社会信用代码
-- 1) 其他业务表 tenant_key → tenant_uscc（值由统一社会信用代码回填，见发布说明）；
-- 2) 租户注册表删除 tenant_key 列，uscc 成为唯一标识（无 uscc 的历史租户需人工补代码后方可继续使用）。
SET @c_tk_t = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_tenant' AND column_name = 'tenant_key');
SET @s_tk_t = IF(@c_tk_t > 0, 'ALTER TABLE ssl_ca_tenant DROP COLUMN tenant_key', 'SELECT 1');
PREPARE s_tk_t FROM @s_tk_t; EXECUTE s_tk_t; DEALLOCATE PREPARE s_tk_t;

-- 幂等补列：早于该设计的库补 uscc；补列时允许 NULL，待无空值后（下一段）收紧为 NOT NULL
SET @c_uscc = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_tenant' AND column_name = 'uscc');
SET @s_uscc = IF(@c_uscc = 0, 'ALTER TABLE ssl_ca_tenant ADD COLUMN uscc VARCHAR(18) NULL COMMENT ''统一社会信用代码（GB 32100）：租户唯一标识'' AFTER id', 'SELECT 1');
PREPARE s_uscc FROM @s_uscc; EXECUTE s_uscc; DEALLOCATE PREPARE s_uscc;

-- 无空值时收紧为 NOT NULL（有历史空值行时保持可空，避免启动失败；管理端仍会拒绝新增空代码租户）
SET @n_uscc = (SELECT COUNT(*) FROM ssl_ca_tenant WHERE uscc IS NULL);
SET @s_n_uscc = IF(@n_uscc = 0, 'ALTER TABLE ssl_ca_tenant MODIFY COLUMN uscc VARCHAR(18) NOT NULL COMMENT ''统一社会信用代码（GB 32100）：租户唯一标识 + 登记唯一键''', 'SELECT 1');
PREPARE s_n_uscc FROM @s_n_uscc; EXECUTE s_n_uscc; DEALLOCATE PREPARE s_n_uscc;

-- 幂等唯一索引：同一统一社会信用代码只能登记一个租户
SET @i_uscc = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_tenant' AND index_name = 'uk_uscc');
SET @s_i_uscc = IF(@i_uscc = 0, 'ALTER TABLE ssl_ca_tenant ADD UNIQUE KEY uk_uscc (uscc)', 'SELECT 1');
PREPARE s_i_uscc FROM @s_i_uscc; EXECUTE s_i_uscc; DEALLOCATE PREPARE s_i_uscc;

-- 幂等加列：各业务表归属租户。存量列 tenant_key 直接改名为 tenant_uscc；
-- 模板/用户用 NULL 表达"全局"语义（全局模板对所有租户可见、全局管理员不受限）
SET @c10k = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND column_name = 'tenant_key');
SET @c10u = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND column_name = 'tenant_uscc');
SET @s10 = IF(@c10k > 0,
    'ALTER TABLE ssl_ca_cert CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''所属租户（统一社会信用代码）；default=存量单根兼容值''',
    IF(@c10u = 0, 'ALTER TABLE ssl_ca_cert ADD COLUMN tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''所属租户（统一社会信用代码）；default=存量单根兼容值''', 'SELECT 1'));
PREPARE s10 FROM @s10; EXECUTE s10; DEALLOCATE PREPARE s10;

SET @c11k = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND column_name = 'tenant_key');
SET @c11u = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND column_name = 'tenant_uscc');
SET @s11 = IF(@c11k > 0,
    'ALTER TABLE ssl_cert_application CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''受理租户（统一社会信用代码，签发落到该租户根域）''',
    IF(@c11u = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''受理租户（统一社会信用代码，签发落到该租户根域）''', 'SELECT 1'));
PREPARE s11 FROM @s11; EXECUTE s11; DEALLOCATE PREPARE s11;

-- 幂等加列：申请单提交人/审核人展示用真实姓名。登录账号仍保留在 applied_by/reviewed_by
-- （用于「审核人不得与提交人同一人」「仅提交人本人可撤销」身份校验）；真实姓名在动作发生时落库，
-- 符合 GM/T 0034 审计可追溯要求。历史数据该列为 NULL 时，前端回退显示登录账号。
SET @c_name1 = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND column_name = 'applied_by_name');
SET @s_name1 = IF(@c_name1 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN applied_by_name VARCHAR(64) NULL COMMENT ''提交人真实姓名（登录账号见 applied_by）'' AFTER applied_at', 'SELECT 1');
PREPARE s_name1 FROM @s_name1; EXECUTE s_name1; DEALLOCATE PREPARE s_name1;

SET @c_name2 = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND column_name = 'reviewed_by_name');
SET @s_name2 = IF(@c_name2 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN reviewed_by_name VARCHAR(64) NULL COMMENT ''审核人真实姓名（登录账号见 reviewed_by）'' AFTER reviewed_at', 'SELECT 1');
PREPARE s_name2 FROM @s_name2; EXECUTE s_name2; DEALLOCATE PREPARE s_name2;

SET @c12k = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND column_name = 'tenant_key');
SET @c12u = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND column_name = 'tenant_uscc');
SET @s12 = IF(@c12k > 0,
    'ALTER TABLE ssl_audit_log CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''操作归属租户（统一社会信用代码；default=无在营租户时的兜底）''',
    IF(@c12u = 0, 'ALTER TABLE ssl_audit_log ADD COLUMN tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''操作归属租户（统一社会信用代码；default=无在营租户时的兜底）''', 'SELECT 1'));
PREPARE s12 FROM @s12; EXECUTE s12; DEALLOCATE PREPARE s12;

SET @c13k = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_template' AND column_name = 'tenant_key');
SET @c13u = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_template' AND column_name = 'tenant_uscc');
SET @s13 = IF(@c13k > 0,
    'ALTER TABLE ssl_cert_template CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NULL COMMENT ''归属租户（统一社会信用代码）；NULL=全局模板（所有租户可用）''',
    IF(@c13u = 0, 'ALTER TABLE ssl_cert_template ADD COLUMN tenant_uscc VARCHAR(32) NULL COMMENT ''归属租户（统一社会信用代码）；NULL=全局模板（所有租户可用）''', 'SELECT 1'));
PREPARE s13 FROM @s13; EXECUTE s13; DEALLOCATE PREPARE s13;

SET @c14k = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_sys_user' AND column_name = 'tenant_key');
SET @c14u = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_sys_user' AND column_name = 'tenant_uscc');
SET @s14 = IF(@c14k > 0,
    'ALTER TABLE ssl_sys_user CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NULL COMMENT ''归属租户（统一社会信用代码）；NULL=全局管理员（跨租户）''',
    IF(@c14u = 0, 'ALTER TABLE ssl_sys_user ADD COLUMN tenant_uscc VARCHAR(32) NULL COMMENT ''归属租户（统一社会信用代码）；NULL=全局管理员（跨租户）''', 'SELECT 1'));
PREPARE s14 FROM @s14; EXECUTE s14; DEALLOCATE PREPARE s14;

SET @c15k = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND column_name = 'tenant_key');
SET @c15u = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND column_name = 'tenant_uscc');
SET @s15 = IF(@c15k > 0,
    'ALTER TABLE ssl_crl_state CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''所属租户（统一社会信用代码；每租户独立CRL发布状态行）''',
    IF(@c15u = 0, 'ALTER TABLE ssl_crl_state ADD COLUMN tenant_uscc VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''所属租户（统一社会信用代码；每租户独立CRL发布状态行）''', 'SELECT 1'));
PREPARE s15 FROM @s15; EXECUTE s15; DEALLOCATE PREPARE s15;

-- 平台级保险库（TSA/审计签名/接入密钥）用 NULL 表示不归属任何租户
SET @c15a = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_key_vault' AND column_name = 'tenant_key');
SET @s15a = IF(@c15a > 0, 'ALTER TABLE ssl_key_vault CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NULL COMMENT ''租户域（统一社会信用代码）；NULL=平台级保险库（TSA/审计签名/接入密钥）''', 'SELECT 1');
PREPARE s15a FROM @s15a; EXECUTE s15a; DEALLOCATE PREPARE s15a;

SET @c15b = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_op_ticket' AND column_name = 'tenant_key');
SET @s15b = IF(@c15b > 0, 'ALTER TABLE ssl_cert_op_ticket CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NULL COMMENT ''受理租户（统一社会信用代码）；NULL=存量单根域历史证书的操作单''', 'SELECT 1');
PREPARE s15b FROM @s15b; EXECUTE s15b; DEALLOCATE PREPARE s15b;

SET @c15c = (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ssl_api_client' AND column_name = 'tenant_key');
SET @s15c = IF(@c15c > 0, 'ALTER TABLE ssl_api_client CHANGE COLUMN tenant_key tenant_uscc VARCHAR(32) NOT NULL COMMENT ''绑定租户（统一社会信用代码）：请求租户域由此反查，不信任调用方''', 'SELECT 1');
PREPARE s15c FROM @s15c; EXECUTE s15c; DEALLOCATE PREPARE s15c;

-- 幂等加索引：证书按租户过滤（租户化查询主路径）、审计按租户+时间检索
SET @c16 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND index_name = 'idx_tenant');
SET @s16 = IF(@c16 = 0, 'ALTER TABLE ssl_ca_cert ADD KEY idx_tenant (tenant_uscc, status)', 'SELECT 1');
PREPARE s16 FROM @s16; EXECUTE s16; DEALLOCATE PREPARE s16;

SET @c17 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND index_name = 'idx_tenant_time');
SET @s17 = IF(@c17 = 0, 'ALTER TABLE ssl_audit_log ADD KEY idx_tenant_time (tenant_uscc, created_at)', 'SELECT 1');
PREPARE s17 FROM @s17; EXECUTE s17; DEALLOCATE PREPARE s17;

-- CRL 发布状态每租户一行：tenant_uscc 唯一（并发首建靠唯一索引闭环，代码侧 DuplicateKey 重试兜底）
SET @c18 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND index_name = 'uk_crl_state_tenant');
SET @s18 = IF(@c18 = 0, 'ALTER TABLE ssl_crl_state ADD UNIQUE KEY uk_crl_state_tenant (tenant_uscc)', 'SELECT 1');
PREPARE s18 FROM @s18; EXECUTE s18; DEALLOCATE PREPARE s18;

-- 租户域内按序列号检索（OCSP/verify 域内命中）
SET @c19 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND index_name = 'idx_tenant_serial');
SET @s19 = IF(@c19 = 0, 'ALTER TABLE ssl_ca_cert ADD KEY idx_tenant_serial (tenant_uscc, serial_hex)', 'SELECT 1');
PREPARE s19 FROM @s19; EXECUTE s19; DEALLOCATE PREPARE s19;

-- =============================================================================
-- ===== 角色配置（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡） =====
-- EJBCA 式可配置：11 个预置角色由 RoleCode 枚举固化（builtin=1，编码不可改、不可删）；
-- 支持后台自建自定义角色（builtin=0，可增删、授权上限=全量权限目录由管理员组合）；
-- 预置角色的授权只能在法定上限内收窄（保存与读取双向收口），本表只存档案与授权结果。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ssl_sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_code   VARCHAR(32)  NOT NULL COMMENT '角色编码：预置角色为 ADMIN/OPERATOR/...（枚举固化不可改）；自定义角色由后台自建（builtin=0）',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称（可维护）',
    role_desc   VARCHAR(500) NULL COMMENT '职责说明（对应 GM/T 0034 分权职责）',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序号（升序）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用（停用后该角色账号不可登录）',
    builtin     TINYINT      NOT NULL DEFAULT 1 COMMENT '1-法定内置预置角色（不可删）0-自定义角色（可增删）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理角色档案（预置角色不可删，自定义角色可增删）';

CREATE TABLE IF NOT EXISTS ssl_role_perm (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_code   VARCHAR(32)  NOT NULL COMMENT '角色编码',
    perm_code   VARCHAR(64)  NOT NULL COMMENT '权限点：menu:xxx 页面菜单 / cert:issue 等操作权限',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_code, perm_code),
    KEY idx_perm_code (perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限点授权（保存/读取双向与法定上限取交集）';

CREATE TABLE IF NOT EXISTS ssl_key_vault (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_uscc     VARCHAR(32)  NULL COMMENT '租户域（统一社会信用代码）；NULL=平台级保险库（TSA/审计签名/接入密钥）',
    rel_dir     VARCHAR(128) NOT NULL COMMENT '保险库相对目录（root/subca/tsa/audit-sign/issued/{serial}），沿用原文件路径语义',
    name        VARCHAR(64)  NOT NULL COMMENT '密钥名（key/pub）',
    cipher_hex  TEXT         NOT NULL COMMENT 'SM4 加密后的私钥 PEM（hex 密文）；主密钥（KEK）不入库，走 SSL_MASTER_KEY 或 master.key 文件',
    sealed      TINYINT      NOT NULL DEFAULT 0 COMMENT '1-已封存离线（对应原 .sealed 文件态） 0-在线',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vault_entry (tenant_uscc, rel_dir, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='密钥保险库（私钥 SM4 密文落库，替代原 key.pem.enc 文件形态）';

-- 证书操作审批单（吊销/挂起/解除挂起/证书更新/密钥更新 双人复核：发起→他人审批→批准即执行，GM/T 0034 §10.1g 分权制衡；
-- 直接执行通道已拆除，系统管理员同样只能走单）
CREATE TABLE IF NOT EXISTS ssl_cert_op_ticket (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_uscc       VARCHAR(32)  NULL COMMENT '受理租户（NULL=存量单根域历史证书的操作单）',
    op_no            VARCHAR(32)  NOT NULL COMMENT '操作单号（CO+时间戳+随机，同申请单号规则）',
    op_type          VARCHAR(16)  NOT NULL COMMENT '操作类型：REVOKE-吊销 SUSPEND-挂起 UNSUSPEND-解除挂起 RENEW-证书更新 REKEY-密钥更新',
    cert_id          BIGINT       NOT NULL COMMENT '目标证书ID',
    cert_serial_hex  VARCHAR(64)  NOT NULL COMMENT '目标证书序列号（发起时快照，列表检索用）',
    cert_subject_cn  VARCHAR(255) NOT NULL COMMENT '目标证书主体CN（发起时快照）',
    reason            VARCHAR(255) NULL COMMENT '操作原因（RFC 5280 吊销/挂起原因码；解除挂起无需）',
    validity_days     INT          NULL COMMENT '新有效期天数（RENEW/REKEY 操作单专用，批准后按此天数重签）',
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待审批 EXECUTED-已执行 REJECTED-已驳回 CANCELLED-已撤销',
    requested_by     VARCHAR(64)  NOT NULL COMMENT '发起人账号',
    requested_by_name VARCHAR(64) NULL COMMENT '发起人真实姓名（登录账号见 requested_by）',
    requested_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
    reviewed_by      VARCHAR(64)  NULL COMMENT '审批人账号（不得与发起人同一人）',
    reviewed_by_name VARCHAR(64)  NULL COMMENT '审批人真实姓名',
    reviewed_at      DATETIME     NULL COMMENT '审批时间',
    review_comment   VARCHAR(500) NULL COMMENT '审批意见（驳回必填）',
    executed_at      DATETIME     NULL COMMENT '实际执行进 CRL/OCSP 的时间（批准即执行，同事务）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_op_no (op_no),
    KEY idx_cert (cert_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证书操作审批单（吊销/挂起/更新/重签双人复核）';

-- 幂等补列（存量库升级：validity_days 为 RENEW/REKEY 操作单专用）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_op_ticket' AND COLUMN_NAME = 'validity_days');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE ssl_cert_op_ticket ADD COLUMN validity_days INT NULL COMMENT ''新有效期天数（RENEW/REKEY 操作单专用，批准后按此天数重签）'' AFTER reason',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 幂等播种：cert:op:approve 权限点（DataInitializer 对已存在角色只修剪不补授，新权限需 DDL 显式播种；
-- 审批权给审核员（法定上限）——是否可批仍由「不得与发起人同一人」硬校验把关；
-- ADMIN 已收口为档案/只读 21 项白名单，不含审批权）
INSERT IGNORE INTO ssl_role_perm (role_code, perm_code) VALUES
    ('APPROVER', 'cert:op:approve');

-- ===== HIS 等外部系统在线对接（APP 直连 API） =====

-- 外部 API 客户端：tenant_uscc 由服务端绑定，请求路径不再携带租户标识；
-- app_id/secret 均为随机不可猜串，secret 密文存 ssl_key_vault（api-clients/{app_id}），本表不落明文
CREATE TABLE IF NOT EXISTS ssl_api_client (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    app_id             VARCHAR(64)  NOT NULL COMMENT '随机客户端标识（app_+32位十六进制）',
    tenant_uscc         VARCHAR(32)  NOT NULL COMMENT '绑定租户：请求租户域由此反查，不信任调用方',
    client_name        VARCHAR(64)  NOT NULL COMMENT '客户端名称（审计展示，如 HIS生产前置）',
    ip_allowlist       VARCHAR(500) NULL COMMENT '逗号分隔 IP/CIDR（IPv4 CIDR；空=不限来源）',
    client_cert_sha256 VARCHAR(128) NULL COMMENT 'mTLS 客户端证书 SHA-256 指纹（空=不校验）',
    secret_version     INT          NOT NULL DEFAULT 1 COMMENT 'secret 版本（轮换+1，旧 secret 立即失效）',
    status             TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    last_used_at       DATETIME     NULL COMMENT '最近一次认证通过时间',
    created_by         VARCHAR(64)  NULL COMMENT '登记人',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rotated_at         DATETIME     NULL COMMENT 'secret 最近轮换时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_id (app_id),
    KEY idx_client_tenant (tenant_uscc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部系统 API 客户端（HIS 等；租户绑定+随机凭证）';

-- 幂等补列：申请单外部对接字段（幂等键/对账/来源渠道）
SET @c_ext1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND COLUMN_NAME = 'external_system');
SET @s_ext1 := IF(@c_ext1 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN external_system VARCHAR(64) NULL COMMENT ''外部系统标识（API 客户端 appId）'' AFTER description', 'SELECT 1');
PREPARE s_ext1 FROM @s_ext1; EXECUTE s_ext1; DEALLOCATE PREPARE s_ext1;

SET @c_ext2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND COLUMN_NAME = 'external_request_id');
SET @s_ext2 := IF(@c_ext2 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN external_request_id VARCHAR(64) NULL COMMENT ''外部系统申请唯一ID（幂等键）'' AFTER external_system', 'SELECT 1');
PREPARE s_ext2 FROM @s_ext2; EXECUTE s_ext2; DEALLOCATE PREPARE s_ext2;

SET @c_ext3 := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND COLUMN_NAME = 'external_request_hash');
SET @s_ext3 := IF(@c_ext3 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN external_request_hash VARCHAR(64) NULL COMMENT ''幂等请求体指纹（SHA-256，同ID异体判冲突）'' AFTER external_request_id', 'SELECT 1');
PREPARE s_ext3 FROM @s_ext3; EXECUTE s_ext3; DEALLOCATE PREPARE s_ext3;

SET @c_ext4 := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND COLUMN_NAME = 'external_employee_id');
SET @s_ext4 := IF(@c_ext4 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN external_employee_id VARCHAR(64) NULL COMMENT ''外部员工主键（对账用）'' AFTER external_request_hash', 'SELECT 1');
PREPARE s_ext4 FROM @s_ext4; EXECUTE s_ext4; DEALLOCATE PREPARE s_ext4;

SET @c_ext5 := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND COLUMN_NAME = 'external_employee_code');
SET @s_ext5 := IF(@c_ext5 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN external_employee_code VARCHAR(64) NULL COMMENT ''外部门员工号'' AFTER external_employee_id', 'SELECT 1');
PREPARE s_ext5 FROM @s_ext5; EXECUTE s_ext5; DEALLOCATE PREPARE s_ext5;

SET @c_ext6 := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND COLUMN_NAME = 'external_dept_name');
SET @s_ext6 := IF(@c_ext6 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN external_dept_name VARCHAR(64) NULL COMMENT ''外部科室名称（展示用，不进DN）'' AFTER external_employee_code', 'SELECT 1');
PREPARE s_ext6 FROM @s_ext6; EXECUTE s_ext6; DEALLOCATE PREPARE s_ext6;

SET @c_ext7 := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND COLUMN_NAME = 'channel');
SET @s_ext7 := IF(@c_ext7 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT ''WEB'' COMMENT ''来源渠道：WEB-管理端录入 API-外部系统直连'' AFTER external_dept_name', 'SELECT 1');
PREPARE s_ext7 FROM @s_ext7; EXECUTE s_ext7; DEALLOCATE PREPARE s_ext7;

SET @idx_ext := (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssl_cert_application' AND INDEX_NAME = 'uk_ext_request');
SET @s_ext8 := IF(@idx_ext = 0, 'ALTER TABLE ssl_cert_application ADD UNIQUE KEY uk_ext_request (tenant_uscc, external_system, external_request_id)', 'SELECT 1');
PREPARE s_ext8 FROM @s_ext8; EXECUTE s_ext8; DEALLOCATE PREPARE s_ext8;

-- 租户变更单（暂停/恢复/关闭/重置接入密钥走单双人复核：发起→他人审批→批准即执行；
-- 新建租户已改为直接执行（POST /admin/tenants），本表 CREATE 仅存历史单。接入密钥明文不落库，发起人凭一次性回执从保险库领取）
CREATE TABLE IF NOT EXISTS ssl_tenant_op_ticket (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    op_no                 VARCHAR(32)  NOT NULL COMMENT '变更单号（TO+时间戳+随机）',
    op_type               VARCHAR(20)  NOT NULL COMMENT 'SUSPEND-暂停 RESUME-恢复 CLOSE-关闭 RESET_CREDENTIAL-重置接入密钥（CREATE 仅存历史单）',
    tenant_uscc           VARCHAR(32)  NULL COMMENT '目标租户（统一社会信用代码；历史 CREATE 单为拟登记代码）',
    tenant_name           VARCHAR(64)  NULL COMMENT '租户名称（历史 CREATE 参数快照/审批展示）',
    subject_o             VARCHAR(128) NULL COMMENT '证书主体O（历史 CREATE 参数快照）',
    algorithm             VARCHAR(16)  NULL COMMENT '算法（历史 CREATE 参数快照）',
    contact               VARCHAR(64)  NULL COMMENT '联系人（历史 CREATE 参数快照）',
    contact_phone         VARCHAR(32)  NULL COMMENT '联系方式（历史 CREATE 参数快照）',
    description           VARCHAR(500) NULL COMMENT '说明（历史 CREATE 参数快照）',
    reason                VARCHAR(255) NOT NULL COMMENT '变更事由（审批依据，必填）',
    status                VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING-待审批 EXECUTED-已执行 REJECTED-已驳回 CANCELLED-已撤销',
    requested_by          VARCHAR(64)  NOT NULL COMMENT '发起人账号',
    requested_by_name     VARCHAR(64)  NULL COMMENT '发起人真实姓名',
    requested_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
    reviewed_by           VARCHAR(64)  NULL COMMENT '审批人账号（不得与发起人同一人）',
    reviewed_by_name      VARCHAR(64)  NULL COMMENT '审批人真实姓名',
    reviewed_at           DATETIME     NULL COMMENT '审批时间',
    review_comment        VARCHAR(500) NULL COMMENT '审批意见（驳回必填）',
    executed_at           DATETIME     NULL COMMENT '执行时间（批准即执行，同事务）',
    result_app_id         VARCHAR(64)  NULL COMMENT '执行结果：接入 App ID（RESET_CREDENTIAL 及历史 CREATE 单；密钥明文不落库）',
    result_secret_version INT          NULL COMMENT '执行结果：接入密钥版本',
    receipt_taken_at      DATETIME     NULL COMMENT '接入凭证回执领取时间（一次性，领取后不可再查看）',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_op_no (op_no),
    KEY idx_tenant_op_status (status),
    KEY idx_tenant_op_tenant (tenant_uscc, op_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户变更审批单（暂停/恢复/关闭/接入密钥重置双人复核；历史 CREATE 单可查）';

-- 幂等播种：新建租户（tenant:create，直接执行）、租户变更单菜单与权限
-- （menu:tenant-ops + 发起 tenant:op:apply / 审批 tenant:op:approve）与 API 客户端管理权；
-- 暂停/恢复/关闭/重置接入密钥的旧直接执行权限点已拆除，
-- 存量库中的这些授权由 DataInitializer 按法定上限自动修剪
INSERT IGNORE INTO ssl_role_perm (role_code, perm_code) VALUES
    ('TENANT_ADMIN', 'tenant:create'),
    ('TENANT_ADMIN', 'tenant:op:apply'),
    ('TENANT_ADMIN', 'apiclient:manage'),
    ('TENANT_APPROVER', 'tenant:op:approve'),
    ('TENANT_ADMIN', 'menu:tenant-ops'),
    ('TENANT_APPROVER', 'menu:tenant-ops'),
    ('ADMIN', 'menu:tenant-ops');

-- 幂等播种：ADMIN 全菜单只读巡检权限（存量库升级补授；新库由 DataInitializer 按法定上限播种）。
-- 系统管理员保留账号建号（user:create/user:update），其余业务/租户/角色授权一律只读或不可见
INSERT IGNORE INTO ssl_role_perm (role_code, perm_code) VALUES
    ('ADMIN', 'menu:root-ca'),
    ('ADMIN', 'menu:templates'),
    ('ADMIN', 'menu:audit'),
    ('ADMIN', 'audit:view'),
    ('ADMIN', 'ca:share:view');
