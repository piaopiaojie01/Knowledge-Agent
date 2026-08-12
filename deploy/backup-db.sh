#!/bin/bash
# Knowledge Agent 数据库每日备份
# 用法: ./backup-db.sh
# 建议: crontab -e 添加  0 3 * * * /opt/knowledge-agent/deploy/backup-db.sh

set -e
# P0：口令从 /etc/ka.env 或环境变量注入，仓库不写死密码
if [ -f /etc/ka.env ]; then . /etc/ka.env; fi
BACKUP_DIR="/opt/knowledge-agent/backups"
MYSQL_CONTAINER="ka-mysql"
DB_NAME="knowledge_agent"
DB_USER="ka_user"
DB_PASS="${DB_PASS:?未设置 DB_PASS（可在 /etc/ka.env 配置）}"
KEEP_DAYS=30
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
FILE="$BACKUP_DIR/ka_backup_$TIMESTAMP.sql.gz"

mkdir -p "$BACKUP_DIR"

echo "[$(date)] 开始备份 $DB_NAME ..."
docker exec "$MYSQL_CONTAINER" mysqldump -u"$DB_USER" -p"$DB_PASS" \
    --single-transaction --routines --triggers "$DB_NAME" \
    | gzip > "$FILE"

echo "[$(date)] 备份完成: $FILE ($(du -h "$FILE" | cut -f1))"

# 清理超过 KEEP_DAYS 天的旧备份
DELETED=$(find "$BACKUP_DIR" -name "ka_backup_*.sql.gz" -mtime +$KEEP_DAYS -delete -print | wc -l)
echo "[$(date)] 清理 $DELETED 个过期备份 (保留最近 $KEEP_DAYS 天)"
