-- ============================================================
-- CA 侧两处结构补齐：密钥保险库 KEK 版本标识 + 对外推送投递箱
--
-- 背景（两件事，互不相关，合在一个脚本里只为减少执行次数）：
--   1) ssl_key_vault 只有密文 cipher_hex，没有记录「这条密文是用哪把 KEK 加密的」。
--      换启动目录 / 换 SSL_STORE_DIR 等于换了一把 KEK，症状是接口 500、
--      日志 BadPaddingException: pad block corrupted，且事后无从判断哪条密文该用哪把还原。
--      本次加 kek_id（KEK 指纹），存量行留 NULL 表示「按当前 KEK 解释」。
--   2) 证书状态推送（HisPushService）失败只写日志、不重试，外部系统只能凭 applyNo 人工核对。
--      本次加 ssl_push_outbox 做投递箱：apply_no + event_type 幂等入队，attempt/next_retry_at 控制重试。
--      ssl_message 是站内信（给人看），不能兼任投递队列（需要 attempt / 退避 / 死信）。
--
-- 执行时机：结构先上，代码接线随后（顺序不能反：代码里若先写 kek_id 列会报 Unknown column）。
-- 执行命令：mysql -h <host> -P <port> -u <user> -p --default-character-set=utf8mb4 <db> < alter-kek-id-and-push-outbox.sql
--           （必须带 --default-character-set=utf8mb4，否则中文 COMMENT 会写成乱码）
-- 影响面：只加列、加表，不动既有列语义，不改既有数据。存量证书与既有密钥条目不受影响。
-- 幂等性：两段都可重复执行——加列走 information_schema 判断后动态执行，
--         建表用 CREATE TABLE IF NOT EXISTS，重复跑不会报错。
-- 回滚：见文件末尾（仅在确认无数据写入时使用）。
-- ============================================================


-- ------------------------------------------------------------
-- 第 1 节：ssl_key_vault 增加 KEK 版本标识
--   kek_id 语义：加密本行 cipher_hex 所用的 KEK 指纹（建议为 master.key 的 SHA-256 前 16 位十六进制）。
--   NULL  = 存量行，未打标，按「当前生效的 KEK」解释（与升级前行为一致）。
--   非空  = 明确指向某把 KEK；解密时若与当前 KEK 指纹不一致，应显式报错而不是抛 BadPadding。
-- ------------------------------------------------------------
SET @has_kek_id := (
    SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'ssl_key_vault'
       AND column_name = 'kek_id'
);
SET @ddl := IF(@has_kek_id = 0,
    'ALTER TABLE ssl_key_vault
        ADD COLUMN kek_id VARCHAR(64) NULL
            COMMENT ''加密本行密文所用的 KEK 指纹；NULL=存量行，按当前生效 KEK 解释''
        AFTER cipher_hex',
    'SELECT ''kek_id 已存在，跳过'' AS result');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 核对 1（应看到新增的一行，Position 紧跟 cipher_hex 之后）
-- SHOW COLUMNS FROM ssl_key_vault LIKE 'kek_id';

-- 核对 2（存量行 kek_id 应全为 NULL，表示未打标）
-- SELECT COUNT(*) AS total, SUM(kek_id IS NULL) AS untagged FROM ssl_key_vault;


-- ------------------------------------------------------------
-- 第 2 节：ssl_push_outbox 对外推送投递箱
--   status 取值：PENDING-待投 / SENDING-投递中 / SUCCESS-成功 / FAILED-失败待重试 / DEAD-超次数放弃
--   attempt   ：已投递次数（含失败），用于退避与死信判定
--   退避建议：1m / 5m / 30m / 2h / 6h，attempt >= 6 置 DEAD 并留 last_error 供人工重放
--   幂等     ：uk_outbox_apply_event 保证同一申请单同一事件只入队一次，重发靠 attempt 推进而非新增行
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ssl_push_outbox (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    apply_no            VARCHAR(64)  NOT NULL                COMMENT '证书申请单号（ssl_cert_application.apply_no）',
    event_type          VARCHAR(32)  NOT NULL                COMMENT '推送事件：APP_APPROVED/ISSUED/REVOKED/REJECTED 等',
    target_url          VARCHAR(500) NOT NULL                COMMENT '投递地址（来自 csssl.his-push.callback-url，入队时快照）',
    payload             MEDIUMTEXT   NOT NULL                COMMENT '请求体正文（JSON，重试时原样重放，保证与首次一致）',
    attempt             INT          NOT NULL DEFAULT 0       COMMENT '已投递次数（含失败）',
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SUCCESS/FAILED/DEAD',
    next_retry_at       DATETIME     NULL                    COMMENT '下次投递时间（空=等待首次投递）',
    last_error          VARCHAR(500) NULL                    COMMENT '最近一次失败原因（异常摘要或非 2xx 状态码）',
    last_response       TEXT         NULL                    COMMENT '最近一次响应体（截断保存，便于排查对端拒收原因）',
    company_uscc        VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '所属公司域，与证书/申请单同口径',
    created_by_user_id  BIGINT       NULL                    COMMENT '入队人ID；系统自动入队为 NULL',
    created_by_name     VARCHAR(64)  NULL                    COMMENT '入队人姓名快照；系统自动写「系统」',
    created_at          DATETIME     NOT NULL                COMMENT '创建时间',
    updated_by_user_id  BIGINT       NULL                    COMMENT '修改人ID；系统自动为 NULL',
    updated_by_name     VARCHAR(64)  NULL                    COMMENT '修改人姓名快照',
    updated_at          DATETIME     NULL                    COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_apply_event (apply_no, event_type),
    KEY idx_outbox_due (status, next_retry_at),
    KEY idx_outbox_company (company_uscc, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '对外推送投递箱（可靠投递 + 重试 + 死信留痕）';

-- 核对 3（应看到新表）
-- SHOW TABLES LIKE 'ssl_push_outbox';

-- 核对 4（新表应为空，接入代码后才有数据）
-- SELECT COUNT(*) FROM ssl_push_outbox;


-- ------------------------------------------------------------
-- 执行后仍需的代码接线（本脚本只建结构，不接线）
--   1) KeyVault 写入时写 kek_id = 当前 KEK 指纹；读取时比对，不一致明确报错。
--   2) HisPushService 改为「先入队再异步投递」，失败按退避更新 attempt/next_retry_at；
--      另需一个 @Scheduled 扫描 status='FAILED' 且 next_retry_at <= now 的行重投。
--   3) 管理端加一条「推送失败」查询入口（按 company_uscc/status 过滤），否则死信没人看得见。
-- ------------------------------------------------------------


-- ------------------------------------------------------------
-- 回滚（仅确认无数据写入时使用；执行前先备份对应表）
-- ALTER TABLE ssl_key_vault DROP COLUMN kek_id;
-- DROP TABLE ssl_push_outbox;
-- ------------------------------------------------------------
