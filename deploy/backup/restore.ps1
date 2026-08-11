param(
    [Parameter(Mandatory = $true)][string]$BackupPath,
    [string]$MySqlPwd = "root123",
    [string]$RedisPwd = "ka_redis_dev_2026"
)
# P0 恢复：从 deploy/backup/backups/<时间戳> 恢复 MySQL / Redis / 数据卷
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $BackupPath)) { throw "备份目录不存在: $BackupPath" }
Write-Host "[restore] 从 $BackupPath 恢复（会覆盖现有数据，请先停止业务写入）"

# 1. MySQL
if (Test-Path (Join-Path $BackupPath 'mysql.sql')) {
    docker cp (Join-Path $BackupPath 'mysql.sql') ka-mysql:/tmp/ka_mysql.sql
    docker exec ka-mysql sh -c "mysql -uroot -p$MySqlPwd < /tmp/ka_mysql.sql"
    docker exec ka-mysql rm -f /tmp/ka_mysql.sql
    Write-Host "[restore] MySQL 已恢复"
}

# 2. Redis
if (Test-Path (Join-Path $BackupPath 'redis.rdb')) {
    docker stop ka-redis | Out-Null
    docker cp (Join-Path $BackupPath 'redis.rdb') ka-redis:/data/dump.rdb
    docker start ka-redis | Out-Null
    Write-Host "[restore] Redis 已恢复（容器已重启）"
}

# 3. 数据卷
function Restore-Volume([string]$Vol, [string]$Name, [string]$Src) {
    $tar = Join-Path $Src "$Name.tar.gz"
    if (-not (Test-Path $tar)) { Write-Host "[restore] 跳过（无 $Name.tar.gz）"; return }
    docker run --rm -v "${Vol}:/data" -v "${Src}:/backup" redis:7-alpine sh -c "rm -rf /data/* && tar xzf /backup/${Name}.tar.gz -C /data"
    Write-Host "[restore] volume $Vol 已恢复"
}
Restore-Volume 'knowledgeagent_milvus_data' 'milvus_data' $BackupPath
Restore-Volume 'knowledgeagent_minio_data'  'minio_data'  $BackupPath
Restore-Volume 'knowledgeagent_etcd_data'   'etcd_data'   $BackupPath

Write-Host "[restore] 完成。建议重启相关容器：docker restart ka-milvus ka-minio ka-etcd ka-mysql"
