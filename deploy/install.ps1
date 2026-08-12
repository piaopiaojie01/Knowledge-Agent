# Knowledge Agent 一键部署脚本（交互式配置管理员账号与系统密码）
# 用法:
#   powershell -ExecutionPolicy Bypass -File deploy\install.ps1
#   powershell -ExecutionPolicy Bypass -File deploy\install.ps1 -SkipPrompts   # 仅按已有 .env 启动
param(
    [switch]$SkipPrompts
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot '.env'

function New-RandomSecret([int]$len = 24) {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*-_=+'
    $bytes = New-Object byte[] $len
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in $bytes) { [void]$sb.Append($chars[$b % $chars.Length]) }
    return $sb.ToString()
}

function Read-Secret([string]$label) {
    $sec = Read-Host -Prompt $label -AsSecureString
    if ($null -eq $sec -or $sec.Length -eq 0) { return '' }
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

function Read-OrGenerate([string]$label, [string]$current) {
    if ($current) {
        $v = Read-Host "$label（当前已配置，回车保持不变）"
        return $(if ($v) { $v } else { $current })
    }
    $v = Read-Host "$label（回车自动生成随机密码）"
    return $(if ($v) { $v } else { New-RandomSecret })
}

Write-Host '========================================'
Write-Host ' Knowledge Agent 一键部署'
Write-Host '========================================'

# 0) Docker 检查
Write-Host '[1/5] 检查 Docker ...'
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker 未运行，请先启动 Docker Desktop' }

# 1) 读取已有 .env
$cfg = @{}
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $cfg[$matches[1].Trim()] = $matches[2].Trim() }
    }
}

if (-not $SkipPrompts) {
    Write-Host '[2/5] 配置管理员账号与系统密码 ...'
    $adminUser = Read-Host "系统管理员用户名（默认 admin）"
    if (-not $adminUser) { $adminUser = if ($cfg['KA_ADMIN_USERNAME']) { $cfg['KA_ADMIN_USERNAME'] } else { 'admin' } }
    $adminPwd = Read-Secret '系统管理员密码（8位以上，含大小写/数字/特殊字符；回车跳过）'
    if ($adminPwd -and $adminPwd -notmatch '^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=\[\]{};'':"\\|,.<>/?]).{8,}$') {
        throw '管理员密码不符合复杂度要求（8位以上，含大小写字母、数字和特殊字符）'
    }
    if ($adminPwd) { $cfg['KA_ADMIN_USERNAME'] = $adminUser; $cfg['KA_ADMIN_PASSWORD'] = $adminPwd }
    elseif (-not $cfg['KA_ADMIN_PASSWORD']) {
        Write-Host '[提示] 未设置管理员密码，将使用默认种子账号 admin/admin123，首次登录后请通过管理后台重置'
    }

    $cfg['MYSQL_ROOT_PASSWORD'] = Read-OrGenerate 'MySQL root 密码' $cfg['MYSQL_ROOT_PASSWORD']
    $cfg['DB_PASS']             = Read-OrGenerate '应用数据库密码（DB_PASS）' $cfg['DB_PASS']
    $cfg['REDIS_PASS']          = Read-OrGenerate 'Redis 密码' $cfg['REDIS_PASS']
    $cfg['MINIO_ACCESS_KEY']    = Read-OrGenerate 'MinIO AccessKey' $cfg['MINIO_ACCESS_KEY']
    $cfg['MINIO_SECRET_KEY']    = Read-OrGenerate 'MinIO SecretKey' $cfg['MINIO_SECRET_KEY']
    $cfg['JWT_SECRET']          = Read-OrGenerate 'JWT 签名密钥' $cfg['JWT_SECRET']
    $cfg['KA_INTERNAL_API_KEY'] = Read-OrGenerate 'Agent 内部密钥（后端/Agent 共用）' $cfg['KA_INTERNAL_API_KEY']
    if (-not $cfg['DB_USER']) { $cfg['DB_USER'] = 'ka_user' }
    if (-not $cfg.ContainsKey('KA_DEEPSEEK_API_KEY')) { $cfg['KA_DEEPSEEK_API_KEY'] = '' }
    $secure = Read-Host 'HTTPS 部署？（y/n，默认 n）'
    $cfg['KA_COOKIE_SECURE'] = if ($secure -match '^y') { 'true' } else { 'false' }
} else {
    Write-Host '[2/5] 跳过交互（-SkipPrompts），使用现有 .env'
    foreach ($k in @('MYSQL_ROOT_PASSWORD','DB_PASS','REDIS_PASS','MINIO_ACCESS_KEY','MINIO_SECRET_KEY','JWT_SECRET','KA_INTERNAL_API_KEY')) {
        if (-not $cfg[$k]) { throw "缺少 $k，请先运行 install.ps1 或手工填写 .env" }
    }
}

# 2) 端口占用检查（本机 8080 常被其他应用占用，允许改端口）
if (-not $cfg['BACKEND_PORT']) { $cfg['BACKEND_PORT'] = '8080' }
if (-not $cfg['HTTP_PORT']) { $cfg['HTTP_PORT'] = '80' }
if (-not $cfg['HTTPS_PORT']) { $cfg['HTTPS_PORT'] = '443' }
if (Get-NetTCPConnection -LocalPort $cfg['BACKEND_PORT'] -State Listen -ErrorAction SilentlyContinue) {
    $np = Read-Host "后端端口 $($cfg['BACKEND_PORT']) 已被占用，请输入新端口（回车继续，可能启动失败）"
    if ($np) { $cfg['BACKEND_PORT'] = $np }
}
if (Get-NetTCPConnection -LocalPort $cfg['HTTP_PORT'] -State Listen -ErrorAction SilentlyContinue) {
    $np = Read-Host "HTTP 端口 $($cfg['HTTP_PORT']) 已被占用，请输入新端口（回车继续）"
    if ($np) { $cfg['HTTP_PORT'] = $np }
}

# 3) 写入 .env（UTF-8 无 BOM，供 docker compose 读取）
Write-Host '[3/5] 写入 .env ...'
$order = @(
    'MYSQL_ROOT_PASSWORD','DB_USER','DB_PASS','REDIS_PASS',
    'MINIO_ACCESS_KEY','MINIO_SECRET_KEY','JWT_SECRET','KA_INTERNAL_API_KEY',
    'KA_DEEPSEEK_API_KEY','KA_COOKIE_SECURE','KA_ADMIN_USERNAME','KA_ADMIN_PASSWORD',
    'BACKEND_PORT','HTTP_PORT','HTTPS_PORT'
)
$lines = @('# 由 install.ps1 生成（已 gitignore，勿提交/勿外泄）')
foreach ($k in $order) { if ($cfg.ContainsKey($k)) { $lines += "$k=$($cfg[$k])" } }
[System.IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding($false)))

# 4) 构建并启动
Write-Host '[4/5] 构建并启动容器（首次构建较慢：agent 镜像含 torch，约 5-15 分钟）...'
docker compose --env-file $envFile -f (Join-Path $PSScriptRoot 'docker-compose.yml') up -d --build
if ($LASTEXITCODE -ne 0) { throw 'docker compose 启动失败，请查看上方日志' }

# 5) 等待后端就绪
Write-Host '[5/5] 等待服务就绪 ...'
$healthUrl = "http://127.0.0.1:$($cfg['BACKEND_PORT'])/api/health"
$ok = $false
for ($i = 0; $i -lt 200; $i++) {
    Start-Sleep -Seconds 3
    try { $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5; if ($r.StatusCode -eq 200) { $ok = $true; break } } catch { }
}

Write-Host '========================================'
if ($ok) {
    Write-Host "部署完成！前端: http://localhost:$($cfg['HTTP_PORT'])"
} else {
    Write-Host '警告: 等待超时，请查看容器日志：docker logs ka-backend'
}
Write-Host "后端健康检查: $healthUrl"
Write-Host "管理员账号: $($cfg['KA_ADMIN_USERNAME'])"
Write-Host "凭据保存在: $envFile"
Write-Host '========================================'
