# 生成醒达（Remindar）正式版签名密钥。
# 密码只在本机终端输入（不显示），保存在 ReminderApp\keystore.properties（已被 .gitignore 排除）。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\ReminderApp\scripts\make_release_key.ps1

$ErrorActionPreference = 'Stop'
$keyDir = 'C:\Users\Administrator\AndroidDev\keys'
$jks = Join-Path $keyDir 'remindar-release.jks'
$props = Join-Path $PSScriptRoot '..\keystore.properties'
$jdk = (Get-ChildItem 'C:\Users\Administrator\AndroidDev' -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName
$keytool = Join-Path $jdk 'bin\keytool.exe'

if (Test-Path $jks) {
    Write-Host "密钥已存在，为避免覆盖已停止：$jks" -ForegroundColor Yellow
    exit 1
}
New-Item -ItemType Directory -Force $keyDir | Out-Null

function Read-Plain([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

Write-Host ''
Write-Host '=== 生成醒达正式版签名密钥 ===' -ForegroundColor Cyan
Write-Host '密码要求：至少 8 位，只用英文字母、数字和常见符号（输入时屏幕上不会显示）'
while ($true) {
    $p1 = Read-Plain '请设置密码'
    $p2 = Read-Plain '请再输入一次'
    if ($p1 -cne $p2) { Write-Host '两次输入不一致，请重新输入' -ForegroundColor Yellow; continue }
    if ($p1.Length -lt 8) { Write-Host '密码至少 8 位' -ForegroundColor Yellow; continue }
    if ($p1 -notmatch '^[\x21-\x7E]+$') { Write-Host '请只使用英文字母、数字和符号，不要用空格或中文' -ForegroundColor Yellow; continue }
    break
}

& $keytool -genkeypair -keystore $jks -storetype PKCS12 -alias remindar `
    -keyalg RSA -keysize 4096 -validity 10000 `
    -storepass $p1 -keypass $p1 -dname 'CN=pedrotung, OU=Remindar, O=Remindar'
if ($LASTEXITCODE -ne 0) { Write-Host '生成失败' -ForegroundColor Red; exit 1 }

# .properties 文件中反斜杠是转义符，需要写成 \\
$escaped = $p1.Replace('\', '\\')
@(
    "storeFile=$($jks -replace '\\', '/')",
    "storePassword=$escaped",
    'keyAlias=remindar',
    "keyPassword=$escaped"
) | Set-Content -Encoding ASCII $props
$p1 = $null; $p2 = $null; $escaped = $null

Write-Host ''
Write-Host '完成！' -ForegroundColor Green
Write-Host "密钥文件：$jks"
Write-Host '请务必把这个密钥文件和密码一起备份（例如 U 盘、网盘加密目录或密码管理器）。' -ForegroundColor Yellow
Write-Host '丢失后将无法再发布可覆盖升级的新版本。' -ForegroundColor Yellow
