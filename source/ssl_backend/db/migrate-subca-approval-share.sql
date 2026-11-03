-- =============================================================================
-- 升级迁移：二级CA重建审批记录新增 share_json 列
-- 用途：审批人审批同意时提交本人持有的根私钥份额（秘钥片），3 名保管人通过即收集到
--       ≥3 份份额，用于执行重建时解封根私钥（无需发起人再次手动粘贴份额）。
-- 适用：已存在 ssl_ca_subca_approval 表的存量库（reset-dev-db.sh 全量重建无需本脚本，
--       因为 db/schema.sql 的 CREATE TABLE 已含该列）。
-- 执行：mysql -h <host> -u <user> -p <db> < db/migrate-subca-approval-share.sql
--       或导入 db/schema.sql（其末尾已含同样的幂等 ALTER 块）。
-- 注意：本脚本含 SET/PREPARE/EXECUTE 会话变量块，须整段作为一个 SQL 批执行。
-- =============================================================================

SET @c_subca = (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'ssl_ca_subca_approval'
                  AND column_name = 'share_json');
SET @s_subca = IF(@c_subca = 0,
    'ALTER TABLE ssl_ca_subca_approval ADD COLUMN share_json TEXT NULL COMMENT ''审批人提交的根私钥份额（秘钥片）JSON；3 名保管人通过即收集到 ≥3 份用于执行重建解封根私钥''',
    'SELECT 1');
PREPARE s_subca FROM @s_subca;
EXECUTE s_subca;
DEALLOCATE PREPARE s_subca;
