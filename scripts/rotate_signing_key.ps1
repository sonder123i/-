<#
.SYNOPSIS
    生成新的发布签名密钥，并创建旧 key -> 新 key 的 APK Signature Scheme v3 轮换谱系。

.DESCRIPTION
    只做本地准备，不改仓库、不碰 CI：
      1. 生成 4096 位 RSA 新 keystore（密码随机，输出到指定目录）
      2. 用 apksigner rotate 生成 lineage.bin
      3. 打印新证书 SHA1/SHA256 与需要写入 GitHub Secrets 的 base64
      4. 可选：用一个未签名 APK 试签并验证

    注意：轮换会改变应用签名身份。已安装旧版本的用户无法通过应用内更新升级
    （旧版本会校验“与当前安装包同签名”），只能手动安装；API 27 设备还需要卸载重装。
    详见 docs/signing-rotation.md。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/rotate_signing_key.ps1
#>
param(
    [string]$OldKeystore = "keystore/GoGoGo.jks",
    [string]$OldStorePass = "GoGoGo",
    [string]$OldAlias = "GoGoKey",
    [string]$OutDir = "$env:USERPROFILE\projects\cloudtrace-signing",
    [string]$NewAlias = "cloudtrace",
    [string]$NewStorePass = "",
    [string]$Apk = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $OldKeystore)) { throw "找不到旧 keystore: $OldKeystore" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$newKeystore = Join-Path $OutDir "cloudtrace-release.jks"
$lineage = Join-Path $OutDir "lineage.bin"

# 未指定密码则生成 24 字节随机密码
if ([string]::IsNullOrWhiteSpace($NewStorePass)) {
    $bytes = New-Object byte[] 18
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $NewStorePass = [Convert]::ToBase64String($bytes)
}

function Find-ApkSigner {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\build-tools",
        "E:\AndroidSdk\build-tools",
        "$env:ANDROID_HOME\build-tools"
    ) | Where-Object { $_ -and (Test-Path $_) }
    foreach ($dir in $candidates) {
        $tool = Get-ChildItem -Path $dir -Filter apksigner.bat -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($tool) { return $tool.FullName }
    }
    throw "找不到 apksigner.bat，请安装 Android Build-Tools"
}

$apksigner = Find-ApkSigner

if (-not (Test-Path $newKeystore)) {
    Write-Host "生成新 keystore: $newKeystore"
    & keytool -genkeypair -v `
        -keystore $newKeystore -storetype PKCS12 `
        -alias $NewAlias -keyalg RSA -keysize 4096 -validity 10000 `
        -storepass $NewStorePass -keypass $NewStorePass `
        -dname "CN=CloudTrace, O=CloudTrace, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool 生成失败" }
} else {
    Write-Host "新 keystore 已存在，未覆盖: $newKeystore"
}

Write-Host "生成轮换谱系: $lineage"
& $apksigner rotate --out $lineage `
    --old-signer --ks $OldKeystore --ks-pass "pass:$OldStorePass" --ks-key-alias $OldAlias `
    --new-signer --ks $newKeystore --ks-pass "pass:$NewStorePass" --ks-key-alias $NewAlias
if ($LASTEXITCODE -ne 0) { throw "apksigner rotate 失败" }

if ($Apk) {
    $signed = Join-Path $OutDir "signed.apk"
    Write-Host "试签 APK: $Apk -> $signed"
    & $apksigner sign --ks $newKeystore --ks-pass "pass:$NewStorePass" `
        --ks-key-alias $NewAlias --lineage $lineage --out $signed $Apk
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign 失败" }
    & $apksigner verify --print-certs --print-rotation-min-sdk-version $signed
}

Write-Host ""
Write-Host "=== 需要备份/写入 GitHub Secrets 的内容 ==="
Write-Host ("NewStorePassword: " + $NewStorePass)
Write-Host ("NewAlias: " + $NewAlias)
Write-Host ""
Write-Host "ROTATE_SIGNING_KEY（base64 keystore）:"
Write-Host ([Convert]::ToBase64String([IO.File]::ReadAllBytes($newKeystore)))
Write-Host ""
Write-Host "SIGNING_LINEAGE（base64 lineage.bin）:"
Write-Host ([Convert]::ToBase64String([IO.File]::ReadAllBytes($lineage)))
Write-Host ""
Write-Host "=== 新证书指纹（用于百度地图安全码）==="
& keytool -list -v -keystore $newKeystore -storepass $NewStorePass -alias $NewAlias |
    Select-String -Pattern "SHA1:|SHA256:"
Write-Host ""
Write-Host "私钥与 lineage 请离线备份；丢失后无法再为已轮换的用户签发更新。"