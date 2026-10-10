# 在 GitHub 上创建 Release 并上传安装包。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\ReminderApp\scripts\publish_release.ps1 -Version 1.0.0
param([string]$Version = '1.0.0')

$gh = 'C:\Users\Administrator\AndroidDev\gh\bin\gh.exe'
$dist = 'C:\Users\Administrator\AndroidDev\dist'
$apk = Join-Path $dist "Remindar-v$Version.apk"
$notes = Join-Path $dist "release-notes-v$Version.md"

& $gh auth status
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host '尚未登录 GitHub，请先运行：gh auth login --web' -ForegroundColor Yellow
    exit 1
}
if (-not (Test-Path $apk)) { Write-Host "找不到安装包：$apk" -ForegroundColor Red; exit 1 }

& $gh release create "v$Version" $apk --repo pedrotung/Remindar `
    --title "醒达 Remindar $Version" --notes-file $notes --latest
if ($LASTEXITCODE -eq 0) {
    Write-Host ''
    Write-Host "发布完成：https://github.com/pedrotung/Remindar/releases/tag/v$Version" -ForegroundColor Green
}
