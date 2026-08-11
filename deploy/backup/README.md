# 备份与恢复

## 备份

覆盖范围：MySQL 逻辑备份、Redis RDB、Milvus 元数据卷、MinIO 段文件卷、etcd 卷、宿主机运行目录（入库任务库/图表/图标）。

```powershell
powershell -ExecutionPolicy Bypass -File deploy\backup\backup.ps1
```

产物在 `deploy\backup\backups\yyyyMMdd_HHmmss\`，默认保留 30 天（`-RetentionDays` 可调）。

### 定时备份（Windows 任务计划）

```powershell
$action  = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-ExecutionPolicy Bypass -File "G:\Knowledge Agent\deploy\backup\backup.ps1"'
$trigger = New-ScheduledTaskTrigger -Daily -At 03:00
Register-ScheduledTask -TaskName 'KA-Backup' -Action $action -Trigger $trigger -RunLevel Limited
```

## 恢复

```powershell
powershell -ExecutionPolicy Bypass -File deploy\backup\restore.ps1 -BackupPath "G:\Knowledge Agent\deploy\backup\backups\20260811_030000"
```

注意：
- 恢复是**覆盖式**操作，请先停止业务写入（或停前端/后端）。
- 数据卷恢复后需重启容器：`docker restart ka-milvus ka-minio ka-etcd ka-mysql`。
- 恢复前建议手动复制一份当前数据（再跑一次备份）。
