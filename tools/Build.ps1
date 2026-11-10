param([ValidateRange(1, 2100000000)][int]$VersionCode = 5)
$ErrorActionPreference = 'Stop'
$taskOriginalRoot = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path -LiteralPath (Join-Path $taskOriginalRoot 'app/libs/libbox.aar'))) {
    & (Join-Path $PSScriptRoot 'Build-Libbox.ps1')
}
$taskDriveLetter = 'R'..'Z' | Where-Object { -not (Test-Path -LiteralPath "${_}:\") } | Select-Object -First 1
if (-not $taskDriveLetter) { throw 'Для сборки нужен один свободный диск R–Z.' }
$taskDrive = "${taskDriveLetter}:"
# Windows AIDL writes ANSI dependency files; an ASCII alias avoids corrupt UTF-8 input.
& subst.exe $taskDrive $taskOriginalRoot
if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать временный ASCII-путь сборки.' }
Push-Location "$taskDrive\"
try {
    . (Join-Path "$taskDrive\" 'tools/Environment.ps1')
    & .\gradlew.bat --no-daemon --console=plain "-PpocketVersionCode=$VersionCode" :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Проверки или сборка Pocket завершились с ошибкой.' }
    & (Join-Path $PSScriptRoot 'Verify-Apk.ps1') -Apk (Join-Path $taskRoot 'app/build/outputs/apk/release/app-release.apk')
    $taskNativeReport=& (Join-Path $PSScriptRoot 'Verify-Native.ps1') -Apk (Join-Path $taskRoot 'app/build/outputs/apk/release/app-release.apk') | Out-String
    $taskNativeExit=$LASTEXITCODE
    $taskNativeEvidence=Join-Path $taskRoot '.artifacts/native-apk-check.json'
    New-Item -ItemType Directory -Force (Split-Path $taskNativeEvidence -Parent) | Out-Null
    $taskNativeReport | Set-Content -LiteralPath $taskNativeEvidence -Encoding utf8
    if ($taskNativeExit -ne 0) { throw 'Нативные библиотеки выпускного APK не прошли проверку; см. .artifacts/native-apk-check.json.' }
    Write-Output 'ABI/ELF/ZIP проверены; отчёт: .artifacts/native-apk-check.json'
} finally {
    Pop-Location
    & subst.exe $taskDrive /D
}
