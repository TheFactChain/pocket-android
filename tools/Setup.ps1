param([Parameter(Mandatory = $true)][string]$AndroidSdk)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
$taskTools = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Force -Path (Join-Path $taskTools 'downloads') | Out-Null

function Install-LockedArchive($Record, [string]$Name, [string]$Executable) {
    $target = Join-Path $taskTools $Record.directory
    if (Test-Path -LiteralPath (Join-Path $target $Executable)) { return }
    if (Test-Path -LiteralPath $target) { throw "Незавершённая установка $Name в $target; автоматическая перезапись запрещена." }
    $archive = Join-Path $taskTools "downloads/$Name.zip"
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Output "Загрузка $Name $($Record.version)"
        Invoke-WebRequest -Uri $Record.url -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $Record.sha256) { throw "Контрольная сумма $Name не совпала; архив не распакован." }
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $taskTools)
    if (-not (Test-Path -LiteralPath (Join-Path $target $Executable))) { throw "В архиве $Name отсутствует $Executable." }
}

Install-LockedArchive $taskLock.jdk 'jdk17' 'bin/java.exe'
Install-LockedArchive $taskLock.go 'go' 'bin/go.exe'
$AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
foreach ($relative in @("platforms/android-$($taskLock.android.compileSdk)/android.jar",
        "build-tools/$($taskLock.android.buildTools)/apksigner.bat",
        "ndk/$($taskLock.android.ndk)/source.properties", 'platform-tools/adb.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk $relative))) { throw "В SDK отсутствует $relative." }
}
Set-Content -LiteralPath (Join-Path $taskRoot 'local.properties') -Value ('sdk.dir=' + $AndroidSdk.Replace('\','/')) -Encoding ascii
. (Join-Path $PSScriptRoot 'Environment.ps1')
$taskJavaVersion = (& java --version | Out-String)
if ($LASTEXITCODE -ne 0 -or $taskJavaVersion -notmatch [regex]::Escape($taskLock.jdk.version)) { throw 'Версия Java не соответствует toolchain.lock.json.' }
$taskGoVersion = (& go version | Out-String)
if ($LASTEXITCODE -ne 0 -or $taskGoVersion -notmatch [regex]::Escape($taskLock.go.version)) { throw 'Версия Go не соответствует toolchain.lock.json.' }
foreach ($binary in @('gomobile', 'gobind')) {
    if (-not (Test-Path -LiteralPath (Join-Path $env:GOBIN "$binary.exe"))) {
        & go install "github.com/sagernet/gomobile/cmd/$binary@$($taskLock.gomobileVersion)"
        if ($LASTEXITCODE -ne 0) { throw "Не удалось подготовить $binary." }
    }
}

$taskSigning = Join-Path $taskRoot '.local/signing'
New-Item -ItemType Directory -Force -Path $taskSigning | Out-Null
$taskIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $taskSigning /inheritance:r /grant:r "${taskIdentity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Не удалось ограничить доступ к новой папке подписи.' }
$taskStore = Join-Path $taskSigning 'pocket.p12'
$taskSecrets = Join-Path $taskSigning 'signing.properties'
if ((Test-Path -LiteralPath $taskStore) -xor (Test-Path -LiteralPath $taskSecrets)) { throw 'Неполный комплект подписи; существующий ключ не заменён.' }
if (-not (Test-Path -LiteralPath $taskStore)) {
    $taskPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $env:POCKET_SETUP_PASSWORD = $taskPassword
    try {
        & keytool -genkeypair -noprompt -alias pocket -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Pocket Android,OU=Pocket,O=Pocket' -storetype PKCS12 -keystore $taskStore -storepass:env POCKET_SETUP_PASSWORD -keypass:env POCKET_SETUP_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать ключ подписи.' }
        @("storePassword=$taskPassword", 'keyAlias=pocket', "keyPassword=$taskPassword") | Set-Content -LiteralPath $taskSecrets -Encoding ascii
    } finally { Remove-Item Env:POCKET_SETUP_PASSWORD -ErrorAction SilentlyContinue; $taskPassword = $null }
}
Write-Output ('Java: ' + $taskLock.jdk.version + '; Go: ' + $taskLock.go.version + '; NDK: ' + $taskLock.android.ndk)
Write-Output 'Инструменты и постоянная локальная подпись подготовлены. Пароли не выводились.'
