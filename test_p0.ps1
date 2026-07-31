Write-Host "=== Weak PW ===" 
$token = ((Invoke-WebRequest -Uri http://localhost:8080/api/auth/login -Method Post -Body '{"username":"admin","password":"admin123"}' -ContentType "application/json").Content | ConvertFrom-Json).data.token

Write-Host "Weak(should fail):"
$r1 = Invoke-WebRequest -Uri http://localhost:8080/api/admin/users -Method Post -Body '{"username":"user001","password":"123"}' -ContentType "application/json" -Headers @{"Authorization"="Bearer $token"}
Write-Host $r1.Content

Write-Host "`nStrong(should pass):"
$r2 = Invoke-WebRequest -Uri http://localhost:8080/api/admin/users -Method Post -Body '{"username":"user001","password":"StrongP@ss1"}' -ContentType "application/json" -Headers @{"Authorization"="Bearer $token"}
Write-Host $r2.Content

Write-Host "`n=== Rate limit: 6 failed logins ==="
for ($i=1; $i -le 6; $i++) {
    $r = Invoke-WebRequest -Uri http://localhost:8080/api/auth/login -Method Post -Body '{"username":"ghost","password":"wrong"}' -ContentType "application/json"
    Write-Host "  #$i $($r.Content)"
}
