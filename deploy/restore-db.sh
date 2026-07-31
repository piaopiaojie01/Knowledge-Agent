#!/bin/bash
# Knowledge Agent 数据库恢复脚本
# 用法: ./restore-db.sh ka_backup_20260730_030000.sql.gz

set -e
BACKUP_FILE="$1"
MYSQL_CONTAINER="ka-mysql"
DB_NAME="knowledge_agent"
DB_USER="ka_user"
DB_PASS="ka_pass_2024"

if [ -z "$BACKUP_FILE" ]; then
    echo "用法: $0 <备份文件.sql.gz>"
    echo "可用备份:"
    ls -lh /opt/knowledge-agent/backups/*.sql.gz 2>/dev/null || echo "  (无)"
    exit 1
fi

if [ ! -f "$BACKUP_FILE" ]; then
    echo "错误: 文件不存在 $BACKUP_FILE"
    exit 1
fi

echo "警告: 即将恢复 $DB_NAME 数据库，当前数据将被覆盖！"
echo "备份文件: $BACKUP_FILE"
read -p "确认恢复? (输入 yes 继续): " CONFIRM

if [ "$CONFIRM" != "yes" ]; then
    echo "已取消"
    exit 0
fi

echo "[$(date)] 开始恢复 ..."
gunzip -c "$BACKUP_FILE" | docker exec -i "$MYSQL_CONTAINER" \
    mysql -u"$DB_USER" -p"$DB_PASS" "$DB_NAME"

echo "[$(date)] 恢复完成"
