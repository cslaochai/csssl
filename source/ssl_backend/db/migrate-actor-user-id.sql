-- =============================================================================
-- csssl CA 平台 —— 存量库迁移：业务表去登录账号，改存 ssl_sys_user.id + 真实姓名快照，
--                       并为全部业务表补齐通用审计列（创建人/创建时间/修改人/修改时间）。
--
-- 为什么要跑：账号（ssl_sys_user.username）是登录凭证，历史实现把它当操作人标识写进了
--   13 张业务表的 21 个列里，接口出参、审计详情、消息正文都跟着往下冒账号。本脚本把这些列
--   一律换成 user_id + 姓名快照，然后把账号列删掉——业务侧再也拿不到账号。
--
-- 执行前提：
--   1) 已备份库（含 ssl_sys_user），已备份磁盘 store-dir/master.key（旧密文靠它解）；
--   2) 后端 8092 停止写入（避免迁移途中又落进旧列）。
--
-- 可重复执行：加列/删列/删索引/回填都按列是否存在判断，回填只补空值；跑第二遍不会报错也不会改坏。
--
-- 执行方式（DELIMITER 是 mysql 客户端指令，需整体按语句顺序执行）：
--   mysql --default-character-set=utf8mb4 -h <host> -P <port> -u <user> -p <db> < migrate-actor-user-id.sql
--
-- 系统身份口径：无法对应到自然人（建根自举、CRL 定时发布、对外 API 通道 api:app_xxx、
--   账号已删除）的行，user_id 留 NULL、姓名列写「系统」或客户端名称，不臆造 id=0 的账号。
--
-- 审计签名口径：ssl_audit_log 的 canonical 串原含登录账号，账号列删除后历史行无法再逐行重算，
--   故历史行统一标 sign_ver=1（旧口径，只校验哈希链前驱指向），新写入的行为 sign_ver=2。
--   不用今天的私钥给历史行重签——那是伪造历史。
--
-- 孤儿清理：站内信以 recipient_user_id 为归属键（DDL 里 NOT NULL），接收账号已被删除的消息
--   无主可读，迁移末尾删除并在自检段报出条数。
-- =============================================================================

-- ---------- 0) 迁移辅助过程（脚本末尾统一清理） ----------

DROP PROCEDURE IF EXISTS csssl_add_column;
CREATE PROCEDURE csssl_add_column(IN t VARCHAR(64), IN c VARCHAR(64), IN ddl VARCHAR(1000))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = t AND column_name = c) THEN
        SET @ddl = CONCAT('ALTER TABLE `', t, '` ADD COLUMN ', ddl);
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

DROP PROCEDURE IF EXISTS csssl_drop_column;
CREATE PROCEDURE csssl_drop_column(IN t VARCHAR(64), IN c VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = t AND column_name = c) THEN
        SET @ddl = CONCAT('ALTER TABLE `', t, '` DROP COLUMN `', c, '`');
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

DROP PROCEDURE IF EXISTS csssl_reindex;
CREATE PROCEDURE csssl_reindex(IN t VARCHAR(64), IN i VARCHAR(64), IN drop_ddl VARCHAR(300), IN add_ddl VARCHAR(300))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = t AND index_name = i) THEN
        SET @ddl = CONCAT('ALTER TABLE `', t, '` ', drop_ddl);
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = t AND index_name = i) THEN
        SET @ddl = CONCAT('ALTER TABLE `', t, '` ', add_ddl);
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

-- 账号列 → user_id + 姓名快照（账号列已删则跳过，保证可重复执行）
DROP PROCEDURE IF EXISTS csssl_backfill_actor;
CREATE PROCEDURE csssl_backfill_actor(IN t VARCHAR(64), IN old_col VARCHAR(64),
                                      IN id_col VARCHAR(64), IN name_col VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = t AND column_name = old_col) THEN
        SET @ddl = CONCAT('UPDATE `', t, '` x JOIN ssl_sys_user u ON u.username = x.`', old_col, '` ',
                          'SET x.`', id_col, '` = u.id, ',
                          'x.`', name_col, '` = IFNULL(NULLIF(x.`', name_col, '`, '''' ), u.real_name) ',
                          'WHERE x.`', id_col, '` IS NULL');
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

-- 对外 API 通道（旧值形如 api:app_xxx）：不是自然人，ID 留空，姓名落客户端名称
DROP PROCEDURE IF EXISTS csssl_backfill_client;
CREATE PROCEDURE csssl_backfill_client(IN t VARCHAR(64), IN old_col VARCHAR(64), IN name_col VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = t AND column_name = old_col) THEN
        SET @ddl = CONCAT('UPDATE `', t, '` x JOIN ssl_api_client c ON c.app_id = SUBSTRING(x.`', old_col, '`, 5) ',
                          'SET x.`', name_col, '` = IFNULL(NULLIF(x.`', name_col, '`, '''' ), c.client_name) ',
                          'WHERE x.`', old_col, '` LIKE ''api:%''');
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

-- 仍对不上自然人的行：姓名落「系统」（SYSTEM 哨兵值与账号列被删/账号已注销两类）
DROP PROCEDURE IF EXISTS csssl_name_system;
CREATE PROCEDURE csssl_name_system(IN t VARCHAR(64), IN id_col VARCHAR(64),
                                   IN name_col VARCHAR(64), IN old_col VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = t AND column_name = old_col) THEN
        SET @ddl = CONCAT('UPDATE `', t, '` SET `', name_col, '` = ''系统'' ',
                          'WHERE `', id_col, '` IS NULL AND (COALESCE(`', old_col, '`, '''') = ''SYSTEM'' ',
                          'OR `', name_col, '` IS NULL OR `', name_col, '` = '''' OR `', name_col, '` = `', old_col, '`)');
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    ELSE
        SET @ddl = CONCAT('UPDATE `', t, '` SET `', name_col, '` = ''系统'' ',
                          'WHERE `', id_col, '` IS NULL AND (`', name_col, '` IS NULL OR `', name_col, '` = '''')');
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

-- 把正文/详情里残留的裸账号换成真实姓名（旧消息正文写过账号，账号列删掉后无法再回查）
DROP PROCEDURE IF EXISTS csssl_scrub_account;
CREATE PROCEDURE csssl_scrub_account(IN t VARCHAR(64), IN col VARCHAR(64), IN old_col VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = t AND column_name = old_col) THEN
        SET @ddl = CONCAT('UPDATE `', t, '` x JOIN ssl_sys_user u ON u.username = x.`', old_col, '` ',
                          'SET x.`', col, '` = REPLACE(x.`', col, '`, u.username, u.real_name) ',
                          'WHERE x.`', col, '` LIKE CONCAT(''%'', u.username, ''%'')');
        PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

-- 逗号分隔的账号串 → 同序的 user_id 串 + 同序的姓名串（匹配不到的账号跳过并计入 skipped）
DROP PROCEDURE IF EXISTS csssl_map_csv;
CREATE PROCEDURE csssl_map_csv(IN csv VARCHAR(1000), OUT mapped VARCHAR(1000),
                               OUT names VARCHAR(1000), OUT skipped INT)
BEGIN
    DECLARE rest VARCHAR(1000);
    DECLARE item VARCHAR(255);
    DECLARE pos INT;
    DECLARE uid BIGINT;
    DECLARE rname VARCHAR(64);
    SET mapped = '';
    SET names = '';
    SET skipped = 0;
    SET rest = NULLIF(TRIM(BOTH ',' FROM TRIM(csv)), '');
    WHILE rest IS NOT NULL DO
        SET pos = LOCATE(',', rest);
        IF pos = 0 THEN
            SET item = rest;
            SET rest = NULL;
        ELSE
            SET item = TRIM(SUBSTRING(rest, 1, pos - 1));
            SET rest = TRIM(SUBSTRING(rest, pos + 1));
            IF rest = '' THEN SET rest = NULL; END IF;
        END IF;
        IF item <> '' THEN
            SET uid = NULL;
            SET rname = NULL;
            SELECT id, real_name INTO uid, rname FROM ssl_sys_user WHERE username = item LIMIT 1;
            IF uid IS NULL THEN
                SET skipped = skipped + 1;
            ELSE
                SET mapped = IF(mapped = '', CAST(uid AS CHAR), CONCAT(mapped, ',', uid));
                SET names = IF(names = '', rname, CONCAT(names, ',', rname));
            END IF;
        END IF;
    END WHILE;
END;

-- 保管人名单快照列逐单换号（重新指派保管人功能上线前的库没有这两列，故整段按存在性兜住）
DROP PROCEDURE IF EXISTS csssl_map_holders;
CREATE PROCEDURE csssl_map_holders()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE tid BIGINT;
    DECLARE holders VARCHAR(500);
    DECLARE voters VARCHAR(500);
    DECLARE ids VARCHAR(1000);
    DECLARE names VARCHAR(1000);
    DECLARE ids2 VARCHAR(1000);
    DECLARE names2 VARCHAR(1000);
    DECLARE skipped INT;
    DECLARE c CURSOR FOR SELECT id, new_holders, voter_usernames FROM ssl_company_op_ticket
                          WHERE new_holders IS NOT NULL OR voter_usernames IS NOT NULL;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'ssl_company_op_ticket'
                      AND column_name = 'new_holders') THEN
        SELECT 1;
    ELSE
        OPEN c;
        walk: LOOP
            FETCH c INTO tid, holders, voters;
            IF done = 1 THEN LEAVE walk; END IF;
            IF holders IS NOT NULL THEN
                CALL csssl_map_csv(holders, ids, names, skipped);
                UPDATE ssl_company_op_ticket
                   SET new_holder_user_ids = NULLIF(ids, ''), new_holder_names = NULLIF(names, '')
                 WHERE id = tid;
            END IF;
            IF voters IS NOT NULL THEN
                CALL csssl_map_csv(voters, ids2, names2, skipped);
                UPDATE ssl_company_op_ticket
                   SET voter_user_ids = NULLIF(ids2, ''), voter_names = NULLIF(names2, '')
                 WHERE id = tid;
            END IF;
        END LOOP;
        CLOSE c;
    END IF;
END;

-- 单张表补齐通用审计六列（已有 created_by_user_id 即视为补过；创建/修改时间缺则单独补）
DROP PROCEDURE IF EXISTS csssl_add_audit;
CREATE PROCEDURE csssl_add_audit(IN t VARCHAR(64))
BEGIN
    CALL csssl_add_column(t, 'created_by_user_id',
        'created_by_user_id BIGINT NULL COMMENT ''创建人ID（ssl_sys_user.id；系统自动写入为 NULL）''');
    CALL csssl_add_column(t, 'created_by_name',
        'created_by_name VARCHAR(64) NULL COMMENT ''创建人真实姓名（系统自动写入写「系统」）''');
    CALL csssl_add_column(t, 'updated_by_user_id',
        'updated_by_user_id BIGINT NULL COMMENT ''修改人ID（ssl_sys_user.id）''');
    CALL csssl_add_column(t, 'updated_by_name',
        'updated_by_name VARCHAR(64) NULL COMMENT ''修改人真实姓名''');
    CALL csssl_add_column(t, 'created_at',
        'created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间''');
    CALL csssl_add_column(t, 'updated_at',
        'updated_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''修改时间''');
END;

-- 全库 ssl_* 表补审计列；ssl_audit_log 自身即审计记录（只追加不修改），不另设修改人列
DROP PROCEDURE IF EXISTS csssl_audit_all;
CREATE PROCEDURE csssl_audit_all()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE t VARCHAR(64);
    DECLARE c CURSOR FOR SELECT table_name FROM information_schema.tables
                          WHERE table_schema = DATABASE() AND table_name LIKE 'ssl\_%'
                            AND table_name <> 'ssl_audit_log'
                          ORDER BY table_name;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    OPEN c;
    walk: LOOP
        FETCH c INTO t;
        IF done = 1 THEN LEAVE walk; END IF;
        CALL csssl_add_audit(t);
    END LOOP;
    CLOSE c;
END;

CALL csssl_audit_all();

-- ---------- 1) 证书申请单：applied_by / reviewed_by / assignee_username ----------

CALL csssl_add_column('ssl_cert_application', 'applied_by_user_id',
    'applied_by_user_id BIGINT NULL COMMENT ''提交人ID（ssl_sys_user.id；对外 API 通道为 NULL）''');
CALL csssl_add_column('ssl_cert_application', 'applied_by_name',
    'applied_by_name VARCHAR(64) NULL COMMENT ''提交人真实姓名（API 通道存客户端名称）''');
CALL csssl_add_column('ssl_cert_application', 'reviewed_by_user_id',
    'reviewed_by_user_id BIGINT NULL COMMENT ''审核人ID（ssl_sys_user.id，不得与提交人同一人）''');
CALL csssl_add_column('ssl_cert_application', 'reviewed_by_name',
    'reviewed_by_name VARCHAR(64) NULL COMMENT ''审核人真实姓名（写入时快照）''');
CALL csssl_add_column('ssl_cert_application', 'assignee_user_id',
    'assignee_user_id BIGINT NULL COMMENT ''归属处理人ID（ssl_sys_user.id）''');
CALL csssl_add_column('ssl_cert_application', 'assignee_real_name',
    'assignee_real_name VARCHAR(64) NULL COMMENT ''归属处理人真实姓名（写入时快照）''');

CALL csssl_backfill_actor('ssl_cert_application', 'applied_by', 'applied_by_user_id', 'applied_by_name');
CALL csssl_backfill_actor('ssl_cert_application', 'reviewed_by', 'reviewed_by_user_id', 'reviewed_by_name');
CALL csssl_backfill_actor('ssl_cert_application', 'assignee_username', 'assignee_user_id', 'assignee_real_name');
CALL csssl_backfill_client('ssl_cert_application', 'applied_by', 'applied_by_name');
CALL csssl_name_system('ssl_cert_application', 'applied_by_user_id', 'applied_by_name', 'applied_by');
CALL csssl_name_system('ssl_cert_application', 'assignee_user_id', 'assignee_real_name', 'assignee_username');

CALL csssl_reindex('ssl_cert_application', 'idx_app_assignee',
    'DROP INDEX idx_app_assignee', 'ADD KEY idx_app_assignee (assignee_user_id, status)');
CALL csssl_drop_column('ssl_cert_application', 'applied_by');
CALL csssl_drop_column('ssl_cert_application', 'reviewed_by');
CALL csssl_drop_column('ssl_cert_application', 'assignee_username');

-- ---------- 2) 证书操作审批单：requested_by / reviewed_by / assignee_username ----------

CALL csssl_add_column('ssl_cert_op_ticket', 'requested_by_user_id',
    'requested_by_user_id BIGINT NULL COMMENT ''发起人ID（ssl_sys_user.id）''');
CALL csssl_add_column('ssl_cert_op_ticket', 'requested_by_name',
    'requested_by_name VARCHAR(64) NULL COMMENT ''发起人真实姓名（写入时快照，不回查用户表）''');
CALL csssl_add_column('ssl_cert_op_ticket', 'reviewed_by_user_id',
    'reviewed_by_user_id BIGINT NULL COMMENT ''审批人ID（ssl_sys_user.id，不得与发起人同一人）''');
CALL csssl_add_column('ssl_cert_op_ticket', 'reviewed_by_name',
    'reviewed_by_name VARCHAR(64) NULL COMMENT ''审批人真实姓名（写入时快照）''');
CALL csssl_add_column('ssl_cert_op_ticket', 'assignee_user_id',
    'assignee_user_id BIGINT NULL COMMENT ''归属处理人ID（ssl_sys_user.id）''');
CALL csssl_add_column('ssl_cert_op_ticket', 'assignee_real_name',
    'assignee_real_name VARCHAR(64) NULL COMMENT ''归属处理人真实姓名（写入时快照）''');

CALL csssl_backfill_actor('ssl_cert_op_ticket', 'requested_by', 'requested_by_user_id', 'requested_by_name');
CALL csssl_backfill_actor('ssl_cert_op_ticket', 'reviewed_by', 'reviewed_by_user_id', 'reviewed_by_name');
CALL csssl_backfill_actor('ssl_cert_op_ticket', 'assignee_username', 'assignee_user_id', 'assignee_real_name');
CALL csssl_name_system('ssl_cert_op_ticket', 'requested_by_user_id', 'requested_by_name', 'requested_by');

CALL csssl_reindex('ssl_cert_op_ticket', 'idx_co_assignee',
    'DROP INDEX idx_co_assignee', 'ADD KEY idx_co_assignee (assignee_user_id, status)');
CALL csssl_drop_column('ssl_cert_op_ticket', 'requested_by');
CALL csssl_drop_column('ssl_cert_op_ticket', 'reviewed_by');
CALL csssl_drop_column('ssl_cert_op_ticket', 'assignee_username');

-- ---------- 3) 公司变更审批单：发起人/审批人/处理人 + 保管人名单快照 ----------

CALL csssl_add_column('ssl_company_op_ticket', 'requested_by_user_id',
    'requested_by_user_id BIGINT NULL COMMENT ''发起人ID（ssl_sys_user.id）''');
CALL csssl_add_column('ssl_company_op_ticket', 'requested_by_name',
    'requested_by_name VARCHAR(64) NULL COMMENT ''发起人真实姓名（写入时快照，不回查用户表）''');
CALL csssl_add_column('ssl_company_op_ticket', 'reviewed_by_user_id',
    'reviewed_by_user_id BIGINT NULL COMMENT ''审批人ID（ssl_sys_user.id，不得与发起人同一人）''');
CALL csssl_add_column('ssl_company_op_ticket', 'reviewed_by_name',
    'reviewed_by_name VARCHAR(64) NULL COMMENT ''审批人真实姓名（写入时快照）''');
CALL csssl_add_column('ssl_company_op_ticket', 'assignee_user_id',
    'assignee_user_id BIGINT NULL COMMENT ''归属处理人ID（ssl_sys_user.id）''');
CALL csssl_add_column('ssl_company_op_ticket', 'assignee_real_name',
    'assignee_real_name VARCHAR(64) NULL COMMENT ''归属处理人真实姓名（写入时快照）''');
CALL csssl_add_column('ssl_company_op_ticket', 'new_holder_user_ids',
    'new_holder_user_ids VARCHAR(500) NULL COMMENT ''新密钥保管人ID名单（REASSIGN_HOLDERS 专用，逗号分隔 ssl_sys_user.id）''');
CALL csssl_add_column('ssl_company_op_ticket', 'new_holder_names',
    'new_holder_names VARCHAR(500) NULL COMMENT ''新密钥保管人姓名名单（与 new_holder_user_ids 同序）''');
CALL csssl_add_column('ssl_company_op_ticket', 'voter_user_ids',
    'voter_user_ids VARCHAR(500) NULL COMMENT ''原密钥保管人ID快照（REASSIGN_HOLDERS 专用，逗号分隔，提交时锁定为投票人）''');
CALL csssl_add_column('ssl_company_op_ticket', 'voter_names',
    'voter_names VARCHAR(500) NULL COMMENT ''原密钥保管人姓名名单（与 voter_user_ids 同序）''');

CALL csssl_backfill_actor('ssl_company_op_ticket', 'requested_by', 'requested_by_user_id', 'requested_by_name');
CALL csssl_backfill_actor('ssl_company_op_ticket', 'reviewed_by', 'reviewed_by_user_id', 'reviewed_by_name');
CALL csssl_backfill_actor('ssl_company_op_ticket', 'assignee_username', 'assignee_user_id', 'assignee_real_name');
CALL csssl_name_system('ssl_company_op_ticket', 'requested_by_user_id', 'requested_by_name', 'requested_by');
CALL csssl_map_holders();

CALL csssl_reindex('ssl_company_op_ticket', 'idx_to_assignee',
    'DROP INDEX idx_to_assignee', 'ADD KEY idx_to_assignee (assignee_user_id, status)');
CALL csssl_drop_column('ssl_company_op_ticket', 'requested_by');
CALL csssl_drop_column('ssl_company_op_ticket', 'reviewed_by');
CALL csssl_drop_column('ssl_company_op_ticket', 'assignee_username');
CALL csssl_drop_column('ssl_company_op_ticket', 'new_holders');
CALL csssl_drop_column('ssl_company_op_ticket', 'voter_usernames');

-- ---------- 4) 保管人投票：voter_username ----------

CALL csssl_add_column('ssl_company_op_vote', 'voter_user_id',
    'voter_user_id BIGINT NULL COMMENT ''投票人ID（ssl_sys_user.id，须在变更单 voter_user_ids 快照内）''');
CALL csssl_backfill_actor('ssl_company_op_vote', 'voter_username', 'voter_user_id', 'voter_real_name');
CALL csssl_reindex('ssl_company_op_vote', 'uk_op_vote_ticket_voter',
    'DROP INDEX uk_op_vote_ticket_voter', 'ADD UNIQUE KEY uk_op_vote_ticket_voter (ticket_id, voter_user_id)');
CALL csssl_drop_column('ssl_company_op_vote', 'voter_username');

-- ---------- 5) 审批轨迹：operator_username ----------

CALL csssl_add_column('ssl_application_trail', 'operator_user_id',
    'operator_user_id BIGINT NULL COMMENT ''操作人ID（ssl_sys_user.id；对外 API 通道为 NULL）''');
CALL csssl_add_column('ssl_application_trail', 'operator_real_name',
    'operator_real_name VARCHAR(64) NULL COMMENT ''操作人真实姓名（写入时快照，不回查用户表）''');
CALL csssl_backfill_actor('ssl_application_trail', 'operator_username', 'operator_user_id', 'operator_real_name');
CALL csssl_backfill_client('ssl_application_trail', 'operator_username', 'operator_real_name');
CALL csssl_name_system('ssl_application_trail', 'operator_user_id', 'operator_real_name', 'operator_username');
CALL csssl_scrub_account('ssl_application_trail', 'comment', 'operator_username');
CALL csssl_drop_column('ssl_application_trail', 'operator_username');

-- ---------- 6) 站内信：recipient_username / sender_username ----------

CALL csssl_add_column('ssl_message', 'recipient_user_id',
    'recipient_user_id BIGINT NULL COMMENT ''接收人ID（ssl_sys_user.id）''');
CALL csssl_add_column('ssl_message', 'recipient_real_name',
    'recipient_real_name VARCHAR(64) NULL COMMENT ''接收人真实姓名（写入时快照，不回查用户表）''');
CALL csssl_add_column('ssl_message', 'sender_user_id',
    'sender_user_id BIGINT NULL COMMENT ''发送人ID（ssl_sys_user.id；系统自动通知为 NULL）''');
CALL csssl_add_column('ssl_message', 'sender_real_name',
    'sender_real_name VARCHAR(64) NULL COMMENT ''发送人真实姓名（系统自动通知写「系统」）''');
CALL csssl_backfill_actor('ssl_message', 'recipient_username', 'recipient_user_id', 'recipient_real_name');
CALL csssl_backfill_actor('ssl_message', 'sender_username', 'sender_user_id', 'sender_real_name');
CALL csssl_name_system('ssl_message', 'sender_user_id', 'sender_real_name', 'sender_username');
CALL csssl_scrub_account('ssl_message', 'content', 'recipient_username');
CALL csssl_scrub_account('ssl_message', 'content', 'sender_username');
CALL csssl_reindex('ssl_message', 'idx_msg_recipient',
    'DROP INDEX idx_msg_recipient', 'ADD KEY idx_msg_recipient (recipient_user_id, read_flag)');
CALL csssl_reindex('ssl_message', 'idx_msg_company',
    'DROP INDEX idx_msg_company', 'ADD KEY idx_msg_company (company_uscc, recipient_user_id)');
CALL csssl_drop_column('ssl_message', 'recipient_username');
CALL csssl_drop_column('ssl_message', 'sender_username');

-- ---------- 7) 根私钥份额保管人绑定：holder_username ----------

CALL csssl_add_column('ssl_company_key_share', 'holder_user_id',
    'holder_user_id BIGINT NULL COMMENT ''份额保管人ID（ssl_sys_user.id，建根时指定，一人一份）''');
CALL csssl_backfill_actor('ssl_company_key_share', 'holder_username', 'holder_user_id', 'holder_real_name');
CALL csssl_reindex('ssl_company_key_share', 'uk_share_company_holder',
    'DROP INDEX uk_share_company_holder', 'ADD UNIQUE KEY uk_share_company_holder (company_uscc, holder_user_id)');
CALL csssl_reindex('ssl_company_key_share', 'idx_share_holder',
    'DROP INDEX idx_share_holder', 'ADD KEY idx_share_holder (holder_user_id)');
CALL csssl_drop_column('ssl_company_key_share', 'holder_username');

-- ---------- 8) 二级CA 重建单与逐人审批 ----------

CALL csssl_add_column('ssl_ca_subca_ticket', 'requested_by_user_id',
    'requested_by_user_id BIGINT NULL COMMENT ''发起人ID（ssl_sys_user.id）''');
CALL csssl_add_column('ssl_ca_subca_ticket', 'requested_by_name',
    'requested_by_name VARCHAR(64) NULL COMMENT ''发起人真实姓名（写入时快照，不回查用户表）''');
CALL csssl_backfill_actor('ssl_ca_subca_ticket', 'requested_by', 'requested_by_user_id', 'requested_by_name');
CALL csssl_name_system('ssl_ca_subca_ticket', 'requested_by_user_id', 'requested_by_name', 'requested_by');
CALL csssl_drop_column('ssl_ca_subca_ticket', 'requested_by');

CALL csssl_add_column('ssl_ca_subca_approval', 'approver_user_id',
    'approver_user_id BIGINT NULL COMMENT ''审批人ID（ssl_sys_user.id，须为该公司 5 名保管人之一）''');
CALL csssl_add_column('ssl_ca_subca_approval', 'approver_real_name',
    'approver_real_name VARCHAR(64) NULL COMMENT ''审批人真实姓名（写入时快照，不回查用户表）''');
CALL csssl_backfill_actor('ssl_ca_subca_approval', 'approver_username', 'approver_user_id', 'approver_real_name');
CALL csssl_reindex('ssl_ca_subca_approval', 'uk_approval_ticket_approver',
    'DROP INDEX uk_approval_ticket_approver',
    'ADD UNIQUE KEY uk_approval_ticket_approver (ticket_id, approver_user_id)');
CALL csssl_drop_column('ssl_ca_subca_approval', 'approver_username');

-- ---------- 9) 证书总账与 API 客户端：created_by 归入通用审计列 ----------

CALL csssl_backfill_actor('ssl_ca_cert', 'created_by', 'created_by_user_id', 'created_by_name');
CALL csssl_name_system('ssl_ca_cert', 'created_by_user_id', 'created_by_name', 'created_by');
CALL csssl_drop_column('ssl_ca_cert', 'created_by');

CALL csssl_backfill_actor('ssl_api_client', 'created_by', 'created_by_user_id', 'created_by_name');
CALL csssl_name_system('ssl_api_client', 'created_by_user_id', 'created_by_name', 'created_by');
CALL csssl_drop_column('ssl_api_client', 'created_by');

-- ---------- 10) 审计日志：username → user_id，历史行标记旧签名口径 ----------

CALL csssl_add_column('ssl_audit_log', 'user_id',
    'user_id BIGINT NULL COMMENT ''操作人ID（ssl_sys_user.id；系统/对外 API 通道为 NULL）''');
CALL csssl_add_column('ssl_audit_log', 'real_name',
    'real_name VARCHAR(64) NULL COMMENT ''操作人真实姓名（展示口径，写入时快照，账号不下沉）''');
CALL csssl_add_column('ssl_audit_log', 'sign_ver',
    'sign_ver TINYINT NOT NULL DEFAULT 1 COMMENT ''防篡改签名口径版本：1-旧（canonical 含登录账号，账号列已删故不再逐行验签）2-新（canonical 含 user_id）''');

CALL csssl_backfill_actor('ssl_audit_log', 'username', 'user_id', 'real_name');
CALL csssl_backfill_client('ssl_audit_log', 'username', 'real_name');
CALL csssl_name_system('ssl_audit_log', 'user_id', 'real_name', 'username');
-- 历史行：签名串算的是账号，账号列删除后不可重算。默认值即 1（旧口径），新代码逐行写 2，
-- 不用今天的私钥给历史行补签——那是伪造历史。
CALL csssl_reindex('ssl_audit_log', 'idx_username_time', 'DROP INDEX idx_username_time', 'SELECT 1');
CALL csssl_reindex('ssl_audit_log', 'idx_user_time', 'SELECT 1', 'ADD KEY idx_user_time (user_id, created_at)');
CALL csssl_drop_column('ssl_audit_log', 'username');

-- ---------- 11) ssl_sys_user 去 company_uscc ----------
-- 数据域从未真正生效：全部账号该列都是 NULL，登录态与令牌里的公司域没有消费方，
-- 公司归属本来就由各业务表自己的 company_uscc 决定，用户表这一列只是历史多租户残留。

CALL csssl_drop_column('ssl_sys_user', 'company_key');
CALL csssl_drop_column('ssl_sys_user', 'company_uscc');

-- ---------- 12) 收敛为 NOT NULL（映射不到的行按口径清理后收紧；已收紧则跳过） ----------

DROP PROCEDURE IF EXISTS csssl_tighten;
CREATE PROCEDURE csssl_tighten(IN t VARCHAR(64), IN c VARCHAR(64), IN cm VARCHAR(200))
BEGIN
    DECLARE bad INT;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = t AND column_name = c
                  AND is_nullable = 'YES') THEN
        SET @q = CONCAT('SELECT COUNT(*) AS n FROM `', t, '` WHERE `', c, '` IS NULL');
        DROP TABLE IF EXISTS csssl_tmp_bad;
        SET @q = CONCAT('CREATE TABLE csssl_tmp_bad AS ', @q);
        PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;
        SELECT n INTO bad FROM csssl_tmp_bad LIMIT 1;
        IF bad > 0 THEN
            SET @q = CONCAT('DELETE FROM `', t, '` WHERE `', c, '` IS NULL');
            PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;
        END IF;
        DROP TABLE IF EXISTS csssl_tmp_bad;
        SET @q = CONCAT('ALTER TABLE `', t, '` MODIFY COLUMN `', c, '` BIGINT NOT NULL COMMENT ''', cm, '''');
        PREPARE st FROM @q; EXECUTE st; DEALLOCATE PREPARE st;
    END IF;
END;

-- 归属/准入键必须落到人：映射不到自然人（账号已注销）的行是孤儿，删除
CALL csssl_tighten('ssl_message', 'recipient_user_id', '接收人ID（ssl_sys_user.id）');
CALL csssl_tighten('ssl_company_key_share', 'holder_user_id', '份额保管人ID（ssl_sys_user.id，建根时指定，一人一份）');
CALL csssl_tighten('ssl_company_op_vote', 'voter_user_id', '投票人ID（ssl_sys_user.id，须在变更单 voter_user_ids 快照内）');
CALL csssl_tighten('ssl_ca_subca_approval', 'approver_user_id', '审批人ID（ssl_sys_user.id，须为该公司 5 名保管人之一）');
CALL csssl_tighten('ssl_cert_op_ticket', 'requested_by_user_id', '发起人ID（ssl_sys_user.id）');
CALL csssl_tighten('ssl_company_op_ticket', 'requested_by_user_id', '发起人ID（ssl_sys_user.id）');
CALL csssl_tighten('ssl_ca_subca_ticket', 'requested_by_user_id', '发起人ID（ssl_sys_user.id）');

-- ---------- 13) 自检：业务表不得再出现账号列，姓名/ID 快照不得双空 ----------

SELECT table_name, column_name, column_type
  FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND table_name <> 'ssl_sys_user'
   AND (column_name LIKE '%username%'
        OR (column_name IN ('created_by', 'applied_by', 'reviewed_by', 'requested_by')
            AND data_type = 'varchar'))
 ORDER BY table_name, column_name;

SELECT 'audit_no_name' k, COUNT(*) n FROM ssl_audit_log WHERE real_name IS NULL
UNION ALL SELECT 'app_no_applier', COUNT(*) FROM ssl_cert_application WHERE applied_by_name IS NULL
UNION ALL SELECT 'trail_no_operator', COUNT(*) FROM ssl_application_trail WHERE operator_real_name IS NULL
UNION ALL SELECT 'message_no_sender', COUNT(*) FROM ssl_message WHERE sender_real_name IS NULL
UNION ALL SELECT 'share_no_holder_name', COUNT(*) FROM ssl_company_key_share WHERE holder_real_name IS NULL
UNION ALL SELECT 'subca_approval_no_name', COUNT(*) FROM ssl_ca_subca_approval WHERE approver_real_name IS NULL
UNION ALL SELECT 'op_ticket_no_requester', COUNT(*) FROM ssl_cert_op_ticket WHERE requested_by_name IS NULL
UNION ALL SELECT 'company_op_no_requester', COUNT(*) FROM ssl_company_op_ticket WHERE requested_by_name IS NULL
UNION ALL SELECT 'audit_legacy_sign', COUNT(*) FROM ssl_audit_log WHERE sign_ver = 1
UNION ALL SELECT 'audit_new_sign', COUNT(*) FROM ssl_audit_log WHERE sign_ver = 2;

-- 正文/详情里若还残留现存账号串，逐条列出来人工确认（正常情况下应为 0 行）
SELECT 'ssl_audit_log' tbl, id, detail AS leaked
  FROM ssl_audit_log a JOIN ssl_sys_user u ON INSTR(a.detail, u.username) > 0
 UNION ALL
SELECT 'ssl_message', m.id, m.content
  FROM ssl_message m JOIN ssl_sys_user u ON INSTR(m.content, u.username) > 0
 UNION ALL
SELECT 'ssl_application_trail', t.id, t.comment
  FROM ssl_application_trail t JOIN ssl_sys_user u ON INSTR(t.comment, u.username) > 0;

-- ---------- 14) 清理辅助过程 ----------

DROP PROCEDURE IF EXISTS csssl_add_column;
DROP PROCEDURE IF EXISTS csssl_drop_column;
DROP PROCEDURE IF EXISTS csssl_reindex;
DROP PROCEDURE IF EXISTS csssl_backfill_actor;
DROP PROCEDURE IF EXISTS csssl_backfill_client;
DROP PROCEDURE IF EXISTS csssl_name_system;
DROP PROCEDURE IF EXISTS csssl_scrub_account;
DROP PROCEDURE IF EXISTS csssl_map_csv;
DROP PROCEDURE IF EXISTS csssl_map_holders;
DROP PROCEDURE IF EXISTS csssl_add_audit;
DROP PROCEDURE IF EXISTS csssl_audit_all;
DROP PROCEDURE IF EXISTS csssl_tighten;
