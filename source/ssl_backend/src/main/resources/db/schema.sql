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
    template_code  VARCHAR(32)  NOT NULL COMMENT '模板编码：SERVER_COMM-机构通信/DOCTOR_SIGN-医生签名/SUB_CA-二级CA',
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
    applicant_type  VARCHAR(16)  NOT NULL COMMENT '申请人类别：INSTITUTION-机构 PERSON-个人',
    applicant_name  VARCHAR(64)  NOT NULL COMMENT '申请人/机构名称',
    subject_dn      VARCHAR(255) NOT NULL COMMENT '证书主体DN（RFC2253，如 CN=张三,O=医院,OU=门诊部）',
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
-- ===== 路线 A：多机构（按机构独立根CA） =====
-- 兼容铁律：存量单根原样成为 default 机构（subject_o='CSSSL'），
-- 其落盘路径沿用现有根路径；新机构路径 store-dir/orgs/{orgKey}/…，互相不信任。
-- =============================================================================

-- 机构注册表：每家医院=一套独立根CA域（根CA+SUB_CA+签发目录+主密钥+门限份额）
CREATE TABLE IF NOT EXISTS ssl_ca_org (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    org_key     VARCHAR(32)  NOT NULL COMMENT '机构标识（小写字母数字，如 his1；default=院内默认域/存量单根）',
    org_name    VARCHAR(64)  NOT NULL COMMENT '机构名称（展示与新根CA CN用）',
    subject_o   VARCHAR(128) NOT NULL COMMENT '证书主体O（机构受控，根/子CA DN 的 O 字段，全局唯一）',
    algorithm   VARCHAR(32)  NOT NULL DEFAULT 'SM2' COMMENT '根CA算法：RSA2048 / SM2（建机构时定，SUB_CA 跟随根）',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用 SUSPENDED-暂停 CLOSED-关闭',
    contact     VARCHAR(64)  NULL COMMENT '联系人/联系方式',
    description VARCHAR(500) NULL COMMENT '备注',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_org_key (org_key),
    UNIQUE KEY uk_subject_o (subject_o)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CA机构注册表（多根CA域隔离）';

-- 幂等加列：各业务表归属机构。存量行由 DEFAULT 'default' 自动归位院内默认域；
-- 模板/用户用 NULL 表达"全局"语义（全局模板对所有机构可见、全局管理员不受限）
SET @c10 = (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND column_name = 'org_key');
SET @s10 = IF(@c10 = 0, 'ALTER TABLE ssl_ca_cert ADD COLUMN org_key VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''所属机构（独立根CA域），default=院内默认域'' AFTER id', 'SELECT 1');
PREPARE s10 FROM @s10; EXECUTE s10; DEALLOCATE PREPARE s10;

SET @c11 = (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_application' AND column_name = 'org_key');
SET @s11 = IF(@c11 = 0, 'ALTER TABLE ssl_cert_application ADD COLUMN org_key VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''受理机构（签发落到该机构根域）'' AFTER id', 'SELECT 1');
PREPARE s11 FROM @s11; EXECUTE s11; DEALLOCATE PREPARE s11;

SET @c12 = (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND column_name = 'org_key');
SET @s12 = IF(@c12 = 0, 'ALTER TABLE ssl_audit_log ADD COLUMN org_key VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''操作归属机构（全局操作记 default）'' AFTER username', 'SELECT 1');
PREPARE s12 FROM @s12; EXECUTE s12; DEALLOCATE PREPARE s12;

SET @c13 = (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'ssl_cert_template' AND column_name = 'org_key');
SET @s13 = IF(@c13 = 0, 'ALTER TABLE ssl_cert_template ADD COLUMN org_key VARCHAR(32) NULL COMMENT ''归属机构；NULL=全局模板（所有机构可用）'' AFTER id', 'SELECT 1');
PREPARE s13 FROM @s13; EXECUTE s13; DEALLOCATE PREPARE s13;

SET @c14 = (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'ssl_sys_user' AND column_name = 'org_key');
SET @s14 = IF(@c14 = 0, 'ALTER TABLE ssl_sys_user ADD COLUMN org_key VARCHAR(32) NULL COMMENT ''归属机构；NULL=全局管理员（跨机构）'' AFTER role_code', 'SELECT 1');
PREPARE s14 FROM @s14; EXECUTE s14; DEALLOCATE PREPARE s14;

SET @c15 = (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND column_name = 'org_key');
SET @s15 = IF(@c15 = 0, 'ALTER TABLE ssl_crl_state ADD COLUMN org_key VARCHAR(32) NOT NULL DEFAULT ''default'' COMMENT ''所属机构（每机构独立CRL发布状态行）'' AFTER id', 'SELECT 1');
PREPARE s15 FROM @s15; EXECUTE s15; DEALLOCATE PREPARE s15;

-- 幂等加索引：证书按机构过滤（机构化查询主路径）、审计按机构+时间检索
SET @c16 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND index_name = 'idx_org');
SET @s16 = IF(@c16 = 0, 'ALTER TABLE ssl_ca_cert ADD KEY idx_org (org_key, status)', 'SELECT 1');
PREPARE s16 FROM @s16; EXECUTE s16; DEALLOCATE PREPARE s16;

SET @c17 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_audit_log' AND index_name = 'idx_org_time');
SET @s17 = IF(@c17 = 0, 'ALTER TABLE ssl_audit_log ADD KEY idx_org_time (org_key, created_at)', 'SELECT 1');
PREPARE s17 FROM @s17; EXECUTE s17; DEALLOCATE PREPARE s17;

-- CRL 发布状态每机构一行：org_key 唯一（并发首建靠唯一索引闭环，代码侧 DuplicateKey 重试兜底）
SET @c18 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_crl_state' AND index_name = 'uk_crl_state_org');
SET @s18 = IF(@c18 = 0, 'ALTER TABLE ssl_crl_state ADD UNIQUE KEY uk_crl_state_org (org_key)', 'SELECT 1');
PREPARE s18 FROM @s18; EXECUTE s18; DEALLOCATE PREPARE s18;

-- 机构域内按序列号检索（OCSP/verify 域内命中）
SET @c19 = (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ssl_ca_cert' AND index_name = 'idx_org_serial');
SET @s19 = IF(@c19 = 0, 'ALTER TABLE ssl_ca_cert ADD KEY idx_org_serial (org_key, serial_hex)', 'SELECT 1');
PREPARE s19 FROM @s19; EXECUTE s19; DEALLOCATE PREPARE s19;

-- =============================================================================
-- ===== 角色配置（GM/T 0034 §8.1.3 多级管理员 / §10.1 分权制衡） =====
-- 六类法定角色固化：编码不可改、角色不可增删，仅可维护名称/职责/排序/启停；
-- 可授权权限点上限由代码内的 PermissionCatalog 固化（保存与读取双向收口），本表只存档案。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ssl_sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_code   VARCHAR(32)  NOT NULL COMMENT '角色编码：ADMIN/OPERATOR/APPROVER/AUDITOR/SECURITY_ADMIN/KEY_ADMIN（法定，不可改）',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称（可维护）',
    role_desc   VARCHAR(500) NULL COMMENT '职责说明（对应 GM/T 0034 分权职责）',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序号（升序）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用（停用后该角色账号不可登录）',
    builtin     TINYINT      NOT NULL DEFAULT 1 COMMENT '1-法定内置角色（角色不可增删）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理角色档案（六类法定角色，不可增删）';

CREATE TABLE IF NOT EXISTS ssl_role_perm (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    role_code   VARCHAR(32)  NOT NULL COMMENT '角色编码',
    perm_code   VARCHAR(64)  NOT NULL COMMENT '权限点：menu:xxx 页面菜单 / cert:issue 等操作权限',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_code, perm_code),
    KEY idx_perm_code (perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限点授权（保存/读取双向与法定上限取交集）';
