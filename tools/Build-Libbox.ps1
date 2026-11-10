param([switch]$VerifySourceOnly)
$ErrorActionPreference = 'Stop'
$taskOriginalRoot = Split-Path $PSScriptRoot -Parent
$taskTag = 'v1.14.4'
$taskCommit = 'c005f35300ce76427dc285481ec5695f5b179cd2'
$taskPatchName = 'libbox-no-config-snapshots.patch'
$taskPatchSHA256 = '90b038a7658d795cf824795578391014153190e4d74e9e3fbb6d3f0d9c07041a'
$taskTags = 'with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0'
$taskLinkerFlags = '-X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid='
$taskDriveLetter = 'R'..'Z' | Where-Object { -not (Test-Path -LiteralPath "${_}:\") } | Select-Object -First 1
if (-not $taskDriveLetter) { throw 'Для сборки нужен один свободный диск R–Z.' }
$taskDrive = "${taskDriveLetter}:"
$taskEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $taskEnvironment[$_.Name] = $_.Value }
# Go, Java and the NDK must all see an ASCII workspace path on Windows.
& subst.exe $taskDrive $taskOriginalRoot
if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать временный ASCII-путь сборки.' }
Push-Location "$taskDrive\"
try {
    . "$taskDrive\tools/Environment.ps1"
    $taskSource = "$taskDrive\.tools/sing-box"
    $taskArtifacts = "$taskDrive\.artifacts/libbox"
    New-Item -ItemType Directory -Force $taskArtifacts, "$taskDrive\app/libs" | Out-Null
    if (-not (Test-Path -LiteralPath "$taskSource/go.mod")) {
        & git clone --depth 1 --branch $taskTag https://github.com/SagerNet/sing-box.git $taskSource
        if ($LASTEXITCODE -ne 0) { throw 'Не удалось загрузить официальный sing-box.' }
    }
    $taskActualCommit = (& git -C $taskSource rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $taskActualCommit -ne $taskCommit) { throw 'Исходники sing-box не соответствуют закреплённому commit.' }
    $taskPatch = "$taskDrive\tools/patches/$taskPatchName"
    if ((Get-FileHash -LiteralPath $taskPatch -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskPatchSHA256) {
        throw 'SHA256 патча libbox не соответствует закреплённому значению.'
    }
    & git -C $taskSource diff --cached --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Индекс sing-box изменён; автоматическая сборка остановлена.' }
    $taskExpectedDiff = [IO.File]::ReadAllText($taskPatch).Replace("`r`n", "`n")
    $taskDiffLines = @(& git -C $taskSource diff --binary --no-ext-diff --no-color HEAD --)
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось проверить diff sing-box.' }
    if ($taskDiffLines.Count -eq 0) {
        & git -C $taskSource apply --check $taskPatch
        if ($LASTEXITCODE -ne 0) { throw 'Патч libbox неприменим к исходному commit.' }
        & git -C $taskSource apply $taskPatch
        if ($LASTEXITCODE -ne 0) { throw 'Не удалось применить патч libbox.' }
        $taskDiffLines = @(& git -C $taskSource diff --binary --no-ext-diff --no-color HEAD --)
        if ($LASTEXITCODE -ne 0) { throw 'Не удалось проверить применённый патч libbox.' }
    }
    if (($taskDiffLines -join "`n") + "`n" -cne $taskExpectedDiff) {
        throw 'Diff sing-box отличается от единственного разрешённого патча; исходники оставлены без сброса.'
    }
    if ($VerifySourceOnly) {
        Write-Host 'Базовый commit, SHA256 патча и точный source diff libbox проверены.'
        return
    }
    $taskGoVersion = & go version
    $taskMobileVersion = & go version -m "$env:GOBIN/gomobile.exe"
    if ($LASTEXITCODE -ne 0) { throw 'Локальный gomobile отсутствует.' }
    Push-Location $taskSource
    try {
        $taskBindArgs = @('bind', '-v', '-o', "$taskArtifacts/libbox.aar", '-target', 'android/arm64,android/arm',
            '-androidapi', '29', '-javapkg=io.nekohasekai', '-libname=box', '-trimpath', '-buildvcs=false',
            '-ldflags', $taskLinkerFlags, '-tags', $taskTags, './experimental/libbox')
        & "$env:GOBIN/gomobile.exe" @taskBindArgs *> "$taskArtifacts/build.log"
        if ($LASTEXITCODE -ne 0) { throw "Сборка libbox завершилась с ошибкой; см. .artifacts/libbox/build.log." }
    } finally { Pop-Location }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $taskArchive = [IO.Compression.ZipFile]::OpenRead("$taskArtifacts/libbox.aar")
    try {
        $taskEntries = @($taskArchive.Entries | ForEach-Object { $_.FullName })
        $taskNativeEntries = @($taskEntries | Where-Object { $_ -match '^jni/.+\.so$' })
        if ($taskNativeEntries.Count -ne 2 -or 'jni/arm64-v8a/libbox.so' -notin $taskEntries -or 'jni/armeabi-v7a/libbox.so' -notin $taskEntries) {
            throw 'AAR не содержит ровно две требуемые ARM ABI.'
        }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($taskArchive.GetEntry('classes.jar'), "$taskArtifacts/classes.jar", $true)
    } finally { $taskArchive.Dispose() }
    $taskClasses = @('Libbox', 'SetupOptions', 'PlatformInterface', 'TunOptions', 'CommandServer', 'CommandServerHandler',
        'CommandClient', 'CommandClientHandler', 'CommandClientOptions', 'StringIterator', 'NetworkInterface',
        'NetworkInterfaceIterator', 'InterfaceUpdateListener') | ForEach-Object { "io.nekohasekai.libbox.$_" }
    & "$env:JAVA_HOME/bin/javap.exe" -public -classpath "$taskArtifacts/classes.jar" @taskClasses > "$taskArtifacts/api.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось проверить Java API libbox.' }
    Copy-Item -LiteralPath "$taskArtifacts/libbox.aar" -Destination "$taskDrive\app/libs/libbox.aar" -Force
    [ordered]@{
        source = 'https://github.com/SagerNet/sing-box'; tag = $taskTag; commit = $taskActualCommit
        sourcePatches = @([ordered]@{ path = "tools/patches/$taskPatchName"; sha256 = $taskPatchSHA256 })
        sha256 = (Get-FileHash -LiteralPath "$taskArtifacts/libbox.aar" -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath "$taskArtifacts/libbox.aar").Length
        builtUtc = [DateTime]::UtcNow.ToString('o'); go = $taskGoVersion; gomobile = @($taskMobileVersion)
        java = @(& "$env:JAVA_HOME/bin/java.exe" --version); androidApi = 29
        ndk = Split-Path $env:ANDROID_NDK_HOME -Leaf; tags = $taskTags; linkerFlags = $taskLinkerFlags
        bindArguments = @($taskBindArgs | ForEach-Object { $_.Replace($taskDrive, '<workspace>') }); entries = $taskEntries
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$taskArtifacts/build-info.json" -Encoding utf8
    Write-Host "libbox готов: app/libs/libbox.aar; API и сведения: .artifacts/libbox/"
} finally {
    Pop-Location
    & subst.exe $taskDrive /D
    Get-ChildItem Env: | Where-Object { -not $taskEnvironment.ContainsKey($_.Name) } | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    foreach ($taskEntry in $taskEnvironment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($taskEntry.Key, $taskEntry.Value, 'Process') }
}
