param(
    [string]$OutDir = "$env:USERPROFILE\projects\gogogo-update-signing",
    [string]$KeyId = "gogogo-update-2026-11"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$keyPath = Join-Path $OutDir "update-signing-key.pem"
$pubPemPath = Join-Path $OutDir "update-signing-pub.pem"
$pubDerPath = Join-Path $OutDir "update-signing-pub.der"

if (-not (Test-Path $keyPath)) {
    & openssl ecparam -name prime256v1 -genkey -noout -out $keyPath
    if ($LASTEXITCODE -ne 0) { throw "openssl 生成私钥失败" }
    Write-Host "已生成私钥: $keyPath"
} else {
    Write-Host "私钥已存在，未覆盖: $keyPath"
}

& openssl ec -in $keyPath -pubout -out $pubPemPath
if ($LASTEXITCODE -ne 0) { throw "openssl 导出公钥失败" }
& openssl pkey -pubin -in $pubPemPath -outform DER -out $pubDerPath
if ($LASTEXITCODE -ne 0) { throw "openssl 导出 DER 公钥失败" }

$publicBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($pubDerPath))

Write-Host ""
Write-Host "请把私钥离线备份到安全位置（丢失后更新通道永久失效）:"
Write-Host "  $keyPath"
Write-Host ""
Write-Host "把下面这一行合并进 app/src/main/assets/app-update-keys.json："
Write-Host "{"
Write-Host "  `"$KeyId`": `"$publicBase64`""
Write-Host "}"
