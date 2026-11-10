-- ============================================================
-- 对外接入凭证：增加「调用方 SM2 签名公钥」列
--
-- 背景：对外 API 鉴权由 HMAC 共享口令升级为 SM2 客户端签名。
--   非空 = 该 appId 走非对称验签（私钥在调用方手里，CA 只存公钥）；
--   为空 = 仍走 HMAC 共享口令（过渡期兼容存量接入方，可逐个切换）。
--
-- 执行时机：升级代码后、重启服务前必须执行，否则 ssl_api_client 的查询会报 Unknown column。
-- 幂等性：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 Duplicate column name，可忽略。
-- ============================================================

ALTER TABLE ssl_api_client
    ADD COLUMN sign_public_key VARCHAR(130) NULL
        COMMENT '调用方SM2签名公钥（130字符hex，04前缀）；非空=非对称验签，空=HMAC共享口令';

-- 核对（应看到新增的一行）
-- SHOW COLUMNS FROM ssl_api_client LIKE 'sign_public_key';
