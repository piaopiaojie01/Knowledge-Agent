param(
    [string]$BackupDir = "G:\Knowledge Agent\deploy\backup\backups",
    [int]$RetentionDays = 30,
    [string]$MySqlPwd = "root123",
    [string]$RedisPwd = "ka_redis_dev_2026"
)
# P0 备份：MySQL 逻辑备份 + Redis RDB + Milvus/MinIO/etcd 数据卷 + 宿主机运行目录
$ErrorActionPreference = 'Stop'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$target = Join-Path $BackupDir $stamp
New-Item -ItemType Directory -Path $target -Force | Out-Null
Write-Host "[backup] -> $target"

# 1. MySQL 逻辑备份（容器内落盘再拷出，避免 PowerShell 管道破坏编码）
Write-Host "[backup] MySQL dump..."
docker exec ka-mysql sh -c "mysqldump -uroot -p$MySqlPwd --single-transaction --routines --triggers --databases knowledge_agent > /tmp/ka_mysql.sql"
docker cp ka-mysql:/tmp/ka_mysql.sql (Join-Path $target 'mysql.sql')
docker exec ka-mysql rm -f /tmp/ka_mysql.sql

# 2. Redis RDB
Write-Host "[backup] Redis RDB..."
docker exec ka-redis redis-cli -a $RedisPwd SAVE | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[backup] Redis 无口令，使用无鉴权 SAVE"
    docker exec ka-redis redis-cli SAVE | Out-Null
}
docker cp ka-redis:/data/dump.rdb (Join-Path $target 'redis.rdb')

# 3. 数据卷（Milvus 元数据 + MinIO 段文件 + etcd）
function Backup-Volume([string]$Vol, [string]$Name, [string]$Out) {
    # 用本地已有的 redis:7-alpine 做卷打包，避免依赖外网拉取辅助镜像
    docker run --rm -v "${Vol}:/data:ro" -v "${Out}:/backup" redis:7-alpine sh -c "tar czf /backup/${Name}.tar.gz -C /data ."
    Write-Host "[backup] volume $Vol -> $Name.tar.gz"
}
Backup-Volume 'knowledgeagent_milvus_data' 'milvus_data' $target
Backup-Volume 'knowledgeagent_minio_data'  'minio_data'  $target
Backup-Volume 'knowledgeagent_etcd_data'   'etcd_data'   $target

# 4. 宿主机运行目录（入库任务状态/图表/图标）
$hostDirs = @(
    'G:\Knowledge Agent\agent\data',
    'G:\Knowledge Agent\agent\charts',
    'G:\Knowledge Agent\agent\icons',
    'G:\Knowledge Agent\backend\charts',
    'G:\Knowledge Agent\backend\icons'
)
foreach ($d in $hostDirs) {
    if (Test-Path $d) {
        $name = (Split-Path $d -Leaf) + '.zip'
        Compress-Archive -Path (Join-Path $d '*') -DestinationPath (Join-Path $target $name) -Force
        Write-Host "[backup] host dir $d"
    }
}

# 5. 保留策略：删除 N 天前的备份
if ($RetentionDays -gt 0 -and (Test-Path $BackupDir)) {
    Get-ChildItem $BackupDir -Directory | Where-Object {
        $_.Name -match '^\d{8}_\d{6}$' -and $_.LastWriteTime -lt (Get-Date).AddDays(-$RetentionDays)
    } | Remove-Item -Recurse -Force
}

Write-Host "[backup] done: $target"
