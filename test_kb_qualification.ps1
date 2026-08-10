# Knowledge Agent 可用性验收脚本
# 用法: .\test_kb_qualification.ps1
# 覆盖: 服务健康 / 认证与撤销 / 知识库 CRUD / 文档上传入库 / 检索 / RAG 问答 / 权限 / 上传校验
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [string]$AgentUrl = 'http://localhost:8000',
    [string]$FrontUrl = 'http://localhost:9888',
    [string]$AgentKey = 'ka-internal-dev-key',
    [string]$AdminUser = 'admin',
    [string]$AdminPass = 'admin123',
    [string]$ReaderUser = 'qa_reader',
    [string]$ReaderPass = 'Reader@123'
)

$script:pass = 0
$script:fail = 0
$script:fails = New-Object System.Collections.Generic.List[string]

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) {
        $script:pass++
        Write-Host "  [PASS] $name"
    }
    else {
        $script:fail++
        $script:fails.Add($name)
        Write-Host "  [FAIL] $name :: $detail"
    }
}

function Get-StatusOf([System.Net.HttpStatusCode]$code) {
    return [int]$code
}

Write-Host "==== Knowledge Agent 可用性验收 ===="
Write-Host "Base=$BaseUrl Agent=$AgentUrl Front=$FrontUrl"

# ── 1. 服务健康 ──────────────────────────────
Write-Host "`n[1/8] 服务健康"
try {
    $r = Invoke-WebRequest -Uri $FrontUrl -UseBasicParsing -TimeoutSec 10
    Check '前端可达' ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
}
catch { Check '前端可达' $false $_.Exception.Message }

try {
    $r = Invoke-WebRequest -Uri "$AgentUrl/" -UseBasicParsing -TimeoutSec 10
    Check 'Agent 存活' ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
}
catch { Check 'Agent 存活' $false $_.Exception.Message }

try {
    $h = Invoke-RestMethod -Uri "$AgentUrl/api/v1/rag/health" -Headers @{ 'X-KA-API-Key' = $AgentKey } -TimeoutSec 10
    Check 'Agent 健康(带密钥)' ($h.status -eq 'ok') ($h | ConvertTo-Json -Compress)
    Check 'Milvus 已连接' ($h.milvus_connected -eq $true)
    Check 'Embedding 已加载' ($h.embedding_loaded -eq $true)
}
catch { Check 'Agent 健康(带密钥)' $false $_.Exception.Message }

try {
    Invoke-RestMethod -Uri "$AgentUrl/api/v1/rag/health" -TimeoutSec 10 | Out-Null
    Check 'Agent 无密钥被拒' $false '请求竟被放行'
}
catch {
    $code = $_.Exception.Response.StatusCode
    Check 'Agent 无密钥被拒' ([int]$code -eq 401) "HTTP $code"
}

try {
    $bh = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 10
    Check '后端健康' ($bh.message -eq 'success' -or $bh.code -eq 200) ($bh | ConvertTo-Json -Compress)
}
catch { Check '后端健康' $false $_.Exception.Message }

# ── 2. 认证 ──────────────────────────────────
Write-Host "`n[2/8] 认证"
$adminToken = $null
try {
    $l = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = $AdminUser; password = $AdminPass } | ConvertTo-Json) -TimeoutSec 15
    Check '管理员登录' ($l.code -eq 200) ($l | ConvertTo-Json -Compress -Depth 3)
    $adminToken = $l.data.token
}
catch { Check '管理员登录' $false $_.Exception.Message }

$readerToken = $null
$readerUserId = $null
$readerCreated = $false
try {
    # 不依赖预置账号：已存在则直接登录，否则用管理员创建一个强密码测试用户
    $l = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = $ReaderUser; password = $ReaderPass } | ConvertTo-Json) -TimeoutSec 15
    if ($l.code -ne 200) {
        Invoke-RestMethod -Uri "$BaseUrl/api/admin/users" -Method Post -Headers @{ Authorization = "Bearer $adminToken" } `
            -ContentType 'application/json' -Body (@{ username = $ReaderUser; password = $ReaderPass } | ConvertTo-Json) -TimeoutSec 15 | Out-Null
        $readerCreated = $true
        $l = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' `
            -Body (@{ username = $ReaderUser; password = $ReaderPass } | ConvertTo-Json) -TimeoutSec 15
    }
    Check '测试用户登录' ($l.code -eq 200) "code=$($l.code) msg=$($l.message)"
    $readerToken = $l.data.token
    $readerUserId = $l.data.userId
}
catch { Check '测试用户登录' $false $_.Exception.Message }

try {
    $l = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = $AdminUser; password = 'wrong-password' } | ConvertTo-Json) -TimeoutSec 15
    Check '错误密码拒绝' ($l.code -eq 401)
}
catch { Check '错误密码拒绝' $false $_.Exception.Message }

# ── 3. 知识库 CRUD ───────────────────────────
Write-Host "`n[3/8] 知识库"
$kbId = $null
$kbName = "可用性验收_$(Get-Date -Format 'MMddHHmmss')"
try {
    $list = Invoke-RestMethod -Uri "$BaseUrl/api/kb" -Headers @{ Authorization = "Bearer $adminToken" } -TimeoutSec 10
    Check '知识库列表' ($list.code -eq 200) "现有 $($list.data.Count) 个"

    $kb = Invoke-RestMethod -Uri "$BaseUrl/api/kb" -Method Post -Headers @{ Authorization = "Bearer $adminToken" } `
        -ContentType 'application/json' -Body (@{ name = $kbName; description = '验收测试' } | ConvertTo-Json) -TimeoutSec 15
    Check '创建知识库' ($kb.code -eq 200) ($kb | ConvertTo-Json -Compress -Depth 3)
    $kbId = $kb.data.id
    if ($kb.data.isPublic) { Write-Host '  [信息] 新知识库为公开，权限用例将按公开处理' }
}
catch { Check '知识库列表/创建' $false $_.Exception.Message }

# ── 4. 文档上传与入库 ────────────────────────
Write-Host "`n[4/8] 文档上传与入库"
$docId = $null
$marker = "量子榴莲协议-QA-$([DateTime]::Now.Ticks)"
$tmpMd = Join-Path $env:TEMP "ka_qual_$([DateTime]::Now.Ticks).md"
$content = @"
# 可用性验收文档

## $marker

该协议规定，所有验收测试文档必须包含唯一标记，且标记内容应能被向量检索与问答正确引用。
适用场景：企业知识库平台功能验收、检索质量抽检、权限控制验证。
"@
[System.IO.File]::WriteAllText($tmpMd, $content, [System.Text.UTF8Encoding]::new($false))

if ($kbId) {
    try {
        $up = Invoke-RestMethod -Uri "$BaseUrl/api/docs/upload" -Method Post `
            -Headers @{ Authorization = "Bearer $adminToken" } -Form @{
                kbId   = "$kbId"
                file   = Get-Item $tmpMd
            } -TimeoutSec 60
        Check '上传文档' ($up.code -eq 200) ($up | ConvertTo-Json -Compress -Depth 3)
        $docId = $up.data.id
        Check '入库状态受理' ($up.data.docStatus -eq 'PROCESSING') "docStatus=$($up.data.docStatus)"
    }
    catch { Check '上传文档' $false $_.Exception.Message }
}

if ($docId) {
    $deadline = (Get-Date).AddMinutes(3)
    $final = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $d = Invoke-RestMethod -Uri "$BaseUrl/api/docs/$docId" -Headers @{ Authorization = "Bearer $adminToken" } -TimeoutSec 10
            if ($d.data.docStatus -in @('ACTIVE', 'FAILED')) { $final = $d.data; break }
        }
        catch { }
        Start-Sleep -Seconds 5
    }
    if ($final) {
        Check '文档入库完成' ($final.docStatus -eq 'ACTIVE') "status=$($final.docStatus) progress=$($final.ingestProgress) msg=$($final.ingestMessage)"
    }
    else {
        Check '文档入库完成' $false '3 分钟内未落定'
    }
}

# ── 5. 检索 ──────────────────────────────────
Write-Host "`n[5/8] 检索"
if ($kbId) {
    try {
        $kw = [uri]::EscapeDataString('量子榴莲协议')
        $sr = Invoke-RestMethod -Uri "$BaseUrl/api/docs/kb/$kbId/search?keyword=$kw" -Headers @{ Authorization = "Bearer $adminToken" } -TimeoutSec 15
        Check '关键词搜索命中' ($sr.code -eq 200 -and $sr.data.Count -ge 1) "命中 $($sr.data.Count) 条"
    }
    catch { Check '关键词搜索命中' $false $_.Exception.Message }

    try {
        $rs = Invoke-RestMethod -Uri "$BaseUrl/api/rag/search" -Method Post -Headers @{ Authorization = "Bearer $adminToken" } `
            -ContentType 'application/json' -Body (@{ question = '量子榴莲协议'; kbNames = @($kbName); topK = 5 } | ConvertTo-Json -Depth 5) -TimeoutSec 60
        $hit = $false
        if ($rs.code -eq 200) {
            foreach ($s in $rs.data.sources) {
                if ($s.content -match '量子榴莲协议') { $hit = $true; break }
            }
        }
        Check '向量检索召回' ($rs.code -eq 200 -and $hit) "code=$($rs.code) results=$($rs.data.sources.Count)"
    }
    catch { Check '向量检索召回' $false $_.Exception.Message }
}

# ── 6. RAG 问答 ──────────────────────────────
Write-Host "`n[6/8] RAG 问答"
if ($kbId) {
    try {
        $q = Invoke-RestMethod -Uri "$BaseUrl/api/rag/query" -Method Post -Headers @{ Authorization = "Bearer $adminToken" } `
            -ContentType 'application/json' `
            -Body (@{ question = '量子榴莲协议是什么？'; kbNames = @($kbName) } | ConvertTo-Json -Depth 5) -TimeoutSec 120
        $answerOk = ($q.code -eq 200 -and -not [string]::IsNullOrWhiteSpace($q.data.answer))
        $refOk = ($q.data.answer -match '量子榴莲协议')
        Check 'RAG 问答成功' $answerOk "code=$($q.code) answerLen=$($q.data.answer.Length)"
        Check 'RAG 回答引用测试内容' $refOk ($q.data.answer.Substring(0, [Math]::Min(120, $q.data.answer.Length)))
    }
    catch { Check 'RAG 问答成功' $false $_.Exception.Message }
}

# ── 7. 权限控制 ──────────────────────────────
Write-Host "`n[7/8] 权限控制"
if ($kbId -and $readerToken) {
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/api/permissions/grant" -Method Post -Headers @{ Authorization = "Bearer $adminToken" } `
            -ContentType 'application/json' -Body (@{ username = $ReaderUser; kbId = "$kbId"; permissionType = 'READ' } | ConvertTo-Json) -TimeoutSec 15
        Check '管理员授权 READ' ($r.code -eq 200)
    }
    catch { Check '管理员授权 READ' $false $_.Exception.Message }

    try {
        $rd = Invoke-RestMethod -Uri "$BaseUrl/api/docs/kb/$kbId" -Headers @{ Authorization = "Bearer $readerToken" } -TimeoutSec 10
        Check '被授权用户可读' ($rd.code -eq 200)
    }
    catch { Check '被授权用户可读' $false $_.Exception.Message }

    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/permissions/revoke" -Method Post -Headers @{ Authorization = "Bearer $adminToken" } `
            -ContentType 'application/json' -Body (@{ username = $ReaderUser; kbId = "$kbId" } | ConvertTo-Json) -TimeoutSec 15 | Out-Null
        $blocked = $false
        try {
            Invoke-RestMethod -Uri "$BaseUrl/api/docs/kb/$kbId" -Headers @{ Authorization = "Bearer $readerToken" } -TimeoutSec 10 | Out-Null
        }
        catch { $blocked = $true }
        Check '回收权限后拒绝' $blocked '仍可访问'
    }
    catch { Check '回收权限后拒绝' $false $_.Exception.Message }
}

# ── 8. 上传校验 / Token 撤销 ─────────────────
Write-Host "`n[8/8] 上传校验与 Token 撤销"
if ($kbId) {
    try {
        $bad = Join-Path $env:TEMP "ka_bad_$([DateTime]::Now.Ticks).exe"
        [System.IO.File]::WriteAllText($bad, 'MZ fake exe', [System.Text.UTF8Encoding]::new($false))
        $b = Invoke-RestMethod -Uri "$BaseUrl/api/docs/upload" -Method Post `
            -Headers @{ Authorization = "Bearer $adminToken" } -Form @{ kbId = "$kbId"; file = Get-Item $bad } -TimeoutSec 30
        Check '非法文件类型拒绝' ($b.code -eq 400) ($b | ConvertTo-Json -Compress)
    }
    catch { Check '非法文件类型拒绝' $false $_.Exception.Message }
}

if ($adminToken) {
    try {
        $ref = Invoke-RestMethod -Uri "$BaseUrl/api/auth/refresh" -Method Post -Headers @{ Authorization = "Bearer $adminToken" } -TimeoutSec 15
        Check 'Token 刷新' ($ref.code -eq 200 -and $ref.data.token) '刷新失败'
        if ($ref.data.token) {
            $newToken = $ref.data.token
            $r2 = Invoke-RestMethod -Uri "$BaseUrl/api/kb" -Headers @{ Authorization = "Bearer $newToken" } -TimeoutSec 10
            Check '刷新后 Token 可用' ($r2.code -eq 200)

            Invoke-RestMethod -Uri "$BaseUrl/api/auth/logout" -Method Post -Headers @{ Authorization = "Bearer $newToken" } -TimeoutSec 15 | Out-Null
            $revoked = $false
            try {
                Invoke-RestMethod -Uri "$BaseUrl/api/kb" -Headers @{ Authorization = "Bearer $newToken" } -TimeoutSec 10 | Out-Null
            }
            catch { $revoked = $true }
            Check '登出后 Token 撤销' $revoked '已撤销 token 仍可用'
        }
    }
    catch { Check 'Token 刷新' $false $_.Exception.Message }
}

# ── 清理 ─────────────────────────────────────
Write-Host "`n[清理]"
if ($docId) {
    try { Invoke-RestMethod -Uri "$BaseUrl/api/docs/$docId" -Method Delete -Headers @{ Authorization = "Bearer $adminToken" } -TimeoutSec 30 | Out-Null; Write-Host '  已删除测试文档' }
    catch { Write-Host "  文档清理失败: $($_.Exception.Message)" }
}
if ($kbId) {
    try { Invoke-RestMethod -Uri "$BaseUrl/api/kb/$kbId" -Method Delete -Headers @{ Authorization = "Bearer $adminToken" } -TimeoutSec 30 | Out-Null; Write-Host '  已删除测试知识库' }
    catch { Write-Host "  知识库清理失败: $($_.Exception.Message)" }
}
if ($readerCreated -and $readerUserId) {
    try { Invoke-RestMethod -Uri "$BaseUrl/api/admin/users/$readerUserId" -Method Delete -Headers @{ Authorization = "Bearer $adminToken" } -TimeoutSec 30 | Out-Null; Write-Host '  已删除测试用户' }
    catch { Write-Host "  测试用户清理失败: $($_.Exception.Message)" }
}
Remove-Item $tmpMd, $bad -ErrorAction SilentlyContinue

# ── 汇总 ─────────────────────────────────────
Write-Host "`n========================================"
Write-Host "通过: $($script:pass)   失败: $($script:fail)"
if ($script:fail -eq 0) {
    Write-Host "结论: 知识库可用状态合格 ✅"
    exit 0
}
else {
    Write-Host "失败项:"
    $script:fails | ForEach-Object { Write-Host "  - $_" }
    Write-Host "结论: 知识库可用状态不合格 ❌"
    exit 1
}
