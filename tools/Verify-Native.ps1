param([Parameter(Mandatory)][string]$Apk)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskApk = (Resolve-Path -LiteralPath $Apk).Path
$taskLock = Get-Content (Join-Path $PSScriptRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
$taskSdkLine = Get-Content (Join-Path $taskRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $taskSdkLine) { throw 'local.properties does not contain sdk.dir' }
$taskSdk = $taskSdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$taskZipalign = Join-Path $taskSdk ("build-tools/$($taskLock.android.buildTools)/zipalign.exe")
$taskReadelf = Join-Path $taskSdk ("ndk/$($taskLock.android.ndk)/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe")
if (-not (Test-Path $taskZipalign) -or -not (Test-Path $taskReadelf)) { throw 'Configured zipalign or NDK llvm-readelf is unavailable' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip = [IO.Compression.ZipFile]::OpenRead($taskApk)
$taskTemp = Join-Path ([IO.Path]::GetTempPath()) ('pocket-native-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $taskTemp | Out-Null
$taskFailures = [Collections.Generic.List[string]]::new()
$taskLibraries = @()
try {
    $taskEntries = @($taskZip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
    foreach ($taskAbi in @('arm64-v8a', 'armeabi-v7a')) {
        if ("lib/$taskAbi/libbox.so" -notin $taskEntries.FullName) { $taskFailures.Add("Missing lib/$taskAbi/libbox.so") }
    }
    foreach ($taskEntry in $taskEntries) {
        $taskAbi = $taskEntry.FullName.Split('/')[1]
        if ($taskAbi -notin @('arm64-v8a', 'armeabi-v7a')) { $taskFailures.Add("Unexpected ABI: $taskAbi") }
        $taskFile = Join-Path $taskTemp ($taskAbi + '-' + $taskEntry.Name)
        [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskFile)
        $taskHeaders = @(& $taskReadelf -h -l -W $taskFile 2>&1 | ForEach-Object { "$_" })
        if ($LASTEXITCODE -ne 0) { throw "llvm-readelf failed: $($taskEntry.FullName)" }
        $taskMachine = ($taskHeaders | Where-Object { $_ -match '^\s*Machine:' }).Trim()
        $taskClass = ($taskHeaders | Where-Object { $_ -match '^\s*Class:' }).Trim()
        if (($taskAbi -eq 'arm64-v8a' -and ($taskMachine -notmatch 'AArch64' -or $taskClass -notmatch 'ELF64')) -or
                ($taskAbi -eq 'armeabi-v7a' -and ($taskMachine -notmatch 'Machine:\s+ARM$' -or $taskClass -notmatch 'ELF32'))) {
            $taskFailures.Add("ELF architecture mismatch: $($taskEntry.FullName)")
        }
        $taskLoads = @()
        $taskRequiredAlignment = if ($taskAbi -eq 'arm64-v8a') { 16384 } else { 4096 }
        foreach ($taskHeader in $taskHeaders | Where-Object { $_ -match '^\s*LOAD\s+' }) {
            $taskFields = $taskHeader.Trim() -split '\s+'
            $taskOffset = [Convert]::ToUInt64($taskFields[1].Substring(2), 16)
            $taskAddress = [Convert]::ToUInt64($taskFields[2].Substring(2), 16)
            $taskAlignment = [Convert]::ToUInt64($taskFields[-1].Substring(2), 16)
            $taskPass = $taskAlignment -ge $taskRequiredAlignment -and ($taskOffset % $taskRequiredAlignment) -eq ($taskAddress % $taskRequiredAlignment)
            $taskLoads += [ordered]@{
                offset = $taskOffset; virtualAddress = $taskAddress; alignment = $taskAlignment
                aligned16KiB = $taskAlignment -ge 16384 -and ($taskOffset % 16384) -eq ($taskAddress % 16384)
                compatibleWithAbiPageSize = $taskPass
            }
            if (-not $taskPass) { $taskFailures.Add("ELF LOAD segment does not meet ABI page alignment ($taskRequiredAlignment bytes): $($taskEntry.FullName)") }
        }
        if ($taskLoads.Count -eq 0) { $taskFailures.Add("No ELF LOAD segments: $($taskEntry.FullName)") }
        $taskLibraries += [ordered]@{ entry = $taskEntry.FullName; machine = $taskMachine; elfClass = $taskClass; requiredAlignment = $taskRequiredAlignment; loadSegments = $taskLoads }
    }
} finally {
    $taskZip.Dispose()
    $taskResolvedTemp = [IO.Path]::GetFullPath($taskTemp)
    if (-not $taskResolvedTemp.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected temporary output path' }
    Remove-Item -LiteralPath $taskResolvedTemp -Recurse -Force
}
$taskZipalignOutput = @(& $taskZipalign -c -P 16 -v 4 $taskApk 2>&1 | ForEach-Object { "$_" })
$taskZipalignPassed = $LASTEXITCODE -eq 0
$taskZipalignOutput = @($taskZipalignOutput | ForEach-Object {
    if ($_ -match '^Verifying alignment of ') { 'Verifying alignment of ' + [IO.Path]::GetFileName($taskApk) } else { $_ }
})
if (-not $taskZipalignPassed) { $taskFailures.Add('APK zipalign -c -P 16 -v 4 failed') }
if ($taskZipalignOutput | Where-Object { $_ -match 'lib/[^/]+/[^/]+\.so.*compressed' }) {
    $taskFailures.Add('Native ZIP entries are compressed; direct mmap alignment is not established by this check')
}
[ordered]@{
    apk = [IO.Path]::GetFileName($taskApk)
    sha256 = (Get-FileHash -LiteralPath $taskApk -Algorithm SHA256).Hash.ToLowerInvariant()
    checkedUtc = [DateTime]::UtcNow.ToString('o')
    passed = $taskFailures.Count -eq 0
    libraries = $taskLibraries
    zipalignPassed = $taskZipalignPassed
    zipalignOutput = $taskZipalignOutput
    failures = @($taskFailures)
    scope = 'ELF architecture/LOAD alignment and APK ZIP alignment; no installation or runtime check'
} | ConvertTo-Json -Depth 8
if ($taskFailures.Count -gt 0) { exit 1 }
