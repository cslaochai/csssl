#!/bin/bash
# ============================================================
# csssl CA 平台 —— 一键清库重建（库表 + 磁盘密钥目录 + 测试账号）
#
#   用法：
#     bash db/reset-dev-db.sh [host] [port] [db] [user]
#   例：
#     bash db/reset-dev-db.sh 192.168.88.132 3306 ssl_ca xz_feng
#
#   步骤：
#     1) DROP 全部业务表（db/reset.sql）
#     2) 重新导入 db/schema.sql（建表 + 幂等加列 + 角色/权限/模板种子）
#     3) 建 5 名平台级密钥管理员（db/init-key-admins.sql）
#        —— 不建这 5 个账号，「新建公司」页的「密钥保管人(5人)」下拉就是空的：
#           下拉只认 KEY_ADMIN 角色，而建公司时该公司账号尚不存在 → 只能选平台级账号
#     4) 清空磁盘 store-dir（根私钥密文文件、SHAKIR 份额、主密钥 master.key）
#        —— 必须做：否则残留份额会被"5 份齐全"判据误命中，
#           新公司拿到旧根私钥的份额，后续解封签名挑战必然失败
#
#   ⚠️ 会连同用户账号（ssl_sys_user）一起清掉！执行前务必先备份：
#        备份库（含账号）：导出为 SQL（结构+数据）即可回灌；但 cert_pem 等 PEM 文本
#                          含真实换行，回灌时 INSERT 语句**跨多行**，须累积到以 ");" 结尾
#        备份 master.key ：旧 ssl_key_vault 密文依赖它，丢了旧私钥永久解不开
#      想保留账号只清业务数据 → 改用 db/reset-keep-users.sql
#
#   依赖：mysql 客户端在 PATH 中；schema.sql / reset.sql 与本脚本同目录。
#   注意：口令从环境变量 MYSQL_PWD 读取，避免出现在进程命令行里。
# ============================================================
set -euo pipefail

HOST="${1:-192.168.88.132}"
PORT="${2:-3306}"
DB="${3:-ssl_ca}"
USER="${4:-xz_feng}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA="$HERE/schema.sql"
RESET="$HERE/reset.sql"
INIT_KA="$HERE/init-key-admins.sql"

for f in "$SCHEMA" "$RESET"; do
  [ -f "$f" ] || { echo "✘ 找不到：$f"; exit 1; }
done
command -v mysql >/dev/null 2>&1 || { echo "✘ PATH 中没有 mysql 客户端"; exit 1; }

echo "═══════════════════════════════════════════════════"
echo " 清库目标：$USER@$HOST:$PORT/$DB"
echo "═══════════════════════════════════════════════════"
echo
echo "── 步骤 1/4：DROP 全部业务表 ──"
mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" "$DB" < "$RESET"

echo
echo "── 步骤 2/4：重新导入 schema.sql（建表 + 种子）──"
mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" "$DB" < "$SCHEMA"

if [ -f "$INIT_KA" ]; then
  echo
  echo "── 步骤 3/4：初始化 5 名平台级密钥管理员（保管人下拉的候选）──"
  mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$MYSQL_PWD" "$DB" < "$INIT_KA"
else
  echo
  echo "── 步骤 3/4：跳过（无 init-key-admins.sql）──"
  echo "   ⚠️ 保管人下拉将为空：请手动建 5 个角色为「密钥管理员」的账号"
fi

echo
echo "── 步骤 4/4：清空磁盘 store-dir ──"
# store-dir 默认 ssl_backend/data/certs（相对本脚本上级目录）；可用 CSSSL_STORE_DIR 覆盖
STORE_DIR="${CSSSL_STORE_DIR:-$HERE/../data/certs}"
if [ -d "$STORE_DIR" ]; then
  rm -rf "${STORE_DIR:?}"/* 2>/dev/null || true
  echo "  已清空：$STORE_DIR"
else
  echo "  跳过：$STORE_DIR 不存在"
fi

echo
echo "═══════════════════════════════════════════════════"
echo " ✔ 清库完成。启动后端即可："
echo "   · DataInitializer 自动建 admin/123456 + 12 个预置角色 + 权限种子"
echo "   · 内置证书模板（SERVER_COMM / DOCTOR_SIGN / SUB_CA）自动播种"
echo "   · keyman1~5 / 123456 为 5 名密钥管理员（保管人下拉的候选）"
echo "   · 公司需手工新建（新建即建独立根CA + SUB_CA + 5 份门限份额）"
echo "═══════════════════════════════════════════════════"
