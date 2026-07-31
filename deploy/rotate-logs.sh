#!/bin/bash
# Knowledge Agent 日志轮转脚本
# 建议: crontab 添加  0 4 * * 0 /opt/knowledge-agent/deploy/rotate-logs.sh (每周日凌晨4点)

set -e
MYSQL_CONTAINER="ka-mysql"
DB_NAME="knowledge_agent"
DB_USER="ka_user"
DB_PASS="ka_pass_2024"

echo "[$(date)] 开始审计日志轮转 ..."

# 1. 归档：90天前的日志导出为 SQL 文件
ARCHIVE_FILE="/opt/knowledge-agent/backups/audit_archive_$(date +%Y%m).sql.gz"
docker exec "$MYSQL_CONTAINER" mysqldump -u"$DB_USER" -p"$DB_PASS" \
    --single-transaction --no-create-info "$DB_NAME" audit_logs \
    --where="created_at < DATE_SUB(NOW(), INTERVAL 90 DAY)" \
    | gzip >> "$ARCHIVE_FILE" 2>/dev/null || true

echo "  归档完成: $ARCHIVE_FILE"

# 2. 清理：删除365天前的记录
BEFORE=$(docker exec "$MYSQL_CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" -N -e \
    "SELECT COUNT(*) FROM $DB_NAME.audit_logs WHERE created_at < DATE_SUB(NOW(), INTERVAL 365 DAY)" 2>/dev/null || echo 0)
echo "  SQL数据库优化"
docker exec "$MYSQL_CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" -e \
    "DELETE FROM $DB_NAME.audit_logs WHERE created_at < DATE_SUB(NOW(), INTERVAL 365 DAY)" 2>/dev/null
AFTER=$(docker exec "$MYSQL_CONTAINER" mysql -u"$DB_USER" -p"$DB_PASS" -N -e \
    "SELECT COUNT(*) FROM $DB_NAME.audit_logs" 2>/dev/null || echo "?")

echo "  清理完成: 删除了 $BEFORE 条1年前记录，当前共 $AFTER 条"

# 3. 压缩备份目录中超过 7 天的 .sql 文件
find /opt/knowledge-agent/backups -name "*.sql" -mtime +7 -exec gzip {} \; 2>/dev/null || true

echo "[$(date)] 日志轮转完成"
