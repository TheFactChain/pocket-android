$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskAar = [IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot 'app/libs/libbox.aar'))
$taskBuildInfo = @{}
try {
    foreach ($taskAbi in @('arm64-v8a', 'armeabi-v7a')) {
        $taskTempFile = Join-Path ([IO.Path]::GetTempPath()) ('pocket-notices-' + [guid]::NewGuid().ToString('N') + '.so')
        try {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($taskAar.GetEntry("jni/$taskAbi/libbox.so"), $taskTempFile)
            $taskLines = @(& (Join-Path $taskRoot '.tools/go/bin/go.exe') version -m $taskTempFile)
            if ($LASTEXITCODE -ne 0) { throw "Cannot read Go build info: $taskAbi" }
            $taskActualGo = $taskLines[0].Substring($taskLines[0].LastIndexOf(':') + 1).Trim()
            $taskLines[0] = "libbox.so ($taskAbi): $taskActualGo"
            $taskLines | Set-Content (Join-Path $PSScriptRoot "libbox-$taskAbi-build-info.txt") -Encoding utf8
            $taskBuildInfo[$taskAbi] = @($taskLines | Where-Object { $_ -match '^\s+dep\s+' })
        } finally { if (Test-Path -LiteralPath $taskTempFile) { Remove-Item -LiteralPath $taskTempFile -Force } }
    }
} finally { $taskAar.Dispose() }
if (Compare-Object $taskBuildInfo['arm64-v8a'] $taskBuildInfo['armeabi-v7a']) {
    throw 'ABI module inventories differ; collect their union explicitly before distribution.'
}

function Copy-LicenseFiles([string]$Source, [string]$Destination) {
    $taskLicensePaths = @()
    foreach ($taskFile in Get-ChildItem -LiteralPath $Source -Recurse -File | Where-Object {
        $_.Name -match '^(?i:licen[sc]e|copying|notice|copyright|authors|patents)(?:[._-].*)?$' -and $_.FullName -notmatch '[\\/]\.git[\\/]'
    }) {
        $taskRelative = [IO.Path]::GetRelativePath($Source, $taskFile.FullName)
        $taskDestination = Join-Path $Destination $taskRelative
        New-Item -ItemType Directory -Force (Split-Path $taskDestination -Parent) | Out-Null
        Copy-Item -LiteralPath $taskFile.FullName -Destination $taskDestination -Force
        $taskLicensePaths += [IO.Path]::GetRelativePath($PSScriptRoot, $taskDestination).Replace('\','/')
    }
    return $taskLicensePaths
}

$taskInventory = @()
foreach ($taskLine in $taskBuildInfo['arm64-v8a']) {
    $taskFields = $taskLine.Trim() -split '\s+'
    $taskModule = $taskFields[1]
    $taskVersion = $taskFields[2]
    $taskSum = $taskFields[3]
    $taskEscaped = [regex]::Replace($taskModule, '[A-Z]', { param($m) '!' + $m.Value.ToLowerInvariant() })
    $taskSource = Join-Path $taskRoot ".tools/go-mod/$taskEscaped@$taskVersion"
    if (-not (Test-Path -LiteralPath $taskSource)) { throw "Missing cached source: $taskModule@$taskVersion" }
    $taskDigest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes("$taskModule@$taskVersion"))).Substring(0,12).ToLowerInvariant()
    $taskLicenses = @(Copy-LicenseFiles $taskSource (Join-Path $PSScriptRoot "go-modules/$taskDigest"))
    $taskInventory += [ordered]@{
        module = $taskModule; version = $taskVersion; goSum = $taskSum
        sourceZip = "https://proxy.golang.org/$taskEscaped/@v/$taskVersion.zip"
        sourceMod = "https://proxy.golang.org/$taskEscaped/@v/$taskVersion.mod"
        licenseFiles = $taskLicenses
    }
}
$taskSingBoxLicenses = @(Copy-LicenseFiles (Join-Path $taskRoot '.tools/sing-box') (Join-Path $PSScriptRoot 'sing-box'))
New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'go-runtime') | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot '.tools/go/LICENSE'), (Join-Path $taskRoot '.tools/go/PATENTS') -Destination (Join-Path $PSScriptRoot 'go-runtime') -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'LICENSE') -Destination (Join-Path $PSScriptRoot 'GPL-3.0.txt') -Force
$taskAarHash = (Get-FileHash (Join-Path $taskRoot 'app/libs/libbox.aar') -Algorithm SHA256).Hash.ToLowerInvariant()
$taskNativeBuild = Get-Content (Join-Path $taskRoot '.artifacts/libbox/build-info.json') -Raw | ConvertFrom-Json
if ($taskNativeBuild.sha256 -ne $taskAarHash) { throw 'Native build metadata belongs to another AAR.' }
[ordered]@{
    aarSHA256 = $taskAarHash
    singBoxCommit = 'c005f35300ce76427dc285481ec5695f5b179cd2'
    singBoxSource = 'https://github.com/SagerNet/sing-box/archive/c005f35300ce76427dc285481ec5695f5b179cd2.tar.gz'
    sourcePatches = @($taskNativeBuild.sourcePatches)
    singBoxLicenseFiles = $taskSingBoxLicenses
    goRuntimeSource = 'https://go.dev/dl/go1.25.14.src.tar.gz'
    modules = $taskInventory
} | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $PSScriptRoot 'libbox-dependencies.json') -Encoding utf8
$taskMissing = @($taskInventory | Where-Object { $_.licenseFiles.Count -eq 0 })
Write-Output "Modules: $($taskInventory.Count); modules without license files: $($taskMissing.Count)"
$taskMissing | ForEach-Object { Write-Output "$($_.module)@$($_.version)" }
