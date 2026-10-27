-- =============================================================================
-- CA根认证中心（csssl）数据库脚本，库：ssl_ca
-- 与 csmig（mip_gateway）/ cslk HIS 风格一致：utf8mb4、InnoDB、中文注释
-- =============================================================================

-- 管理用户：登录鉴权（对齐 cslk sys_user / csmig mip_sys_user 核心字段；初始 admin 账号由 DataInitializer 创建）
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
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
    id_no           VARCHAR(32)  NULL COMMENT '证件号（个人身份证/机构统一社会信用代码）',
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
