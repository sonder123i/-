<#
.SYNOPSIS
    发布新版本后，同步生成并提交签名更新清单。

.DESCRIPTION
    从 GitHub Release 下载对应 tag 的 APK，用本地私钥生成 docs/update/stable.json，
    然后提交并推送。私钥始终留在本机，不进入仓库和 CI。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/publish_update_manifest.ps1 -Tag v1.13.0
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Repo = "sonder123i/CloudTrace",
    [string]$KeyFile = "$env:USERPROFILE\projects\gogogo-update-signing\update-signing-key.pem",
    [string]$Notes = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $KeyFile)) {
    throw "找不到更新签名私钥: $KeyFile"
}

$tmp = Join-Path $env:TEMP "cloudtrace-release-$Tag"
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Write-Host "下载 $Tag 的 Release 资产..."
gh release download $Tag -R $Repo -D $tmp
$apk = Get-ChildItem -Path $tmp -Filter *.apk | Select-Object -First 1
if (-not $apk) { throw "Release 中没有 APK" }

$url = "https://github.com/$Repo/releases/download/$Tag/$($apk.Name)"
$mirror = 'gh="GitHub Release"="' + $url + '"'

$owner = $Repo.Split('/')[0]
$giteeUrl = "https://gitee.com/$owner/cloud-traces/releases/download/$Tag/$($apk.Name)"
$giteeMirror = 'gitee="Gitee 发行版"="' + $giteeUrl + '"'
if ([string]::IsNullOrWhiteSpace($Notes)) { $Notes = "云迹 $Tag" }

python scripts/make_update_manifest.py `
    --apk $apk.FullName `
    --mirror $mirror `
    --mirror $giteeMirror `
    --notes $Notes `
    --key-file $KeyFile `
    --out docs/update/stable.json

if ($LASTEXITCODE -ne 0) { throw "生成更新清单失败" }

git add docs/update/stable.json
git commit -m "chore(update): publish manifest for $Tag"
git push

Write-Host "已更新 docs/update/stable.json 并推送。Pages 生效后，应用内检查更新即可看到 $Tag。"