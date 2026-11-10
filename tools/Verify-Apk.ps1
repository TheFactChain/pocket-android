param([Parameter(Mandatory = $true)][string]$Apk)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$Apk = (Resolve-Path -LiteralPath $Apk).Path
$taskBuildTools = Join-Path $env:ANDROID_HOME ('build-tools/' + $taskLock.android.buildTools)
& (Join-Path $taskBuildTools 'apksigner.bat') verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'Подпись APK не прошла проверку.' }
$taskBadging = (& (Join-Path $taskBuildTools 'aapt2.exe') dump badging $Apk | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Не удалось прочитать APK.' }
foreach ($pattern in @("package: name='io.github.nekontritce11123.pocketandroid'", "minSdkVersion:'29'", "application-label:'Pocket'")) {
    if (-not $taskBadging.Contains($pattern)) { throw "В APK не найдено ожидаемое: $pattern" }
}
if ($taskBadging.Contains('application-debuggable')) { throw 'Выпускной APK допускает отладку.' }
$taskManifest = (& (Join-Path $taskBuildTools 'aapt2.exe') dump xmltree $Apk --file AndroidManifest.xml | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Не удалось прочитать итоговый manifest.' }
foreach ($excluded in @('android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.PACKAGE_USAGE_STATS',
        'android.permission.NFC', 'services.FreezeService', 'services.FileShuttleService', 'services.PaymentStubService',
        'util.CrossProfileDocumentsProvider', 'net.typeblog.shelter.action.', 'net.typeblog.shelter.files')) {
    if ($taskManifest.Contains($excluded)) { throw "В APK осталась исключённая возможность: $excluded" }
}
foreach ($required in @('io.github.nekontritce11123.pocketandroid.files', 'ShelterDeviceAdminReceiver', 'FileProviderProxy', 'FinalizeActivity')) {
    if (-not $taskManifest.Contains($required)) { throw "В APK отсутствует необходимый компонент: $required" }
}
Write-Output ('APK проверен: ' + $Apk)
Write-Output ('SHA-256: ' + (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant())
