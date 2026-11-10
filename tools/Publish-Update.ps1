# Prepares a GitHub release of Pocket Android, as Pocket Windows does: the signed APK and
# update-manifest.json (version, size, SHA-256) under tag v<versionName>. Pocket reads
# releases/latest/download/update-manifest.json and installs only an APK with the same signature.
# Without -Publish it only writes the files into .artifacts/release/v<versionName>.
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Notes = '',
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$Repo = 'TheFactChain/pocket-android'
$Apk = (Resolve-Path -LiteralPath $Apk).Path
& (Join-Path $PSScriptRoot 'Verify-Apk.ps1') -Apk $Apk | Out-Null
$badging = (& (Join-Path $env:ANDROID_HOME ('build-tools/' + $taskLock.android.buildTools + '/aapt2.exe')) dump badging $Apk | Out-String)
if ($badging -notmatch "versionCode='(\d+)' versionName='([0-9A-Za-z.\-]+)'") { throw 'Не удалось прочитать версию APK.' }
$code = [int]$Matches[1]; $name = $Matches[2]
if ($Notes.Length -gt 2000) { throw 'Описание выпуска длиннее 2000 символов.' }
$tag = "v$name"; $asset = "Pocket-Android-$name.apk"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) ".artifacts/release/$tag"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item -LiteralPath $Apk -Destination (Join-Path $out $asset) -Force
$file = Get-Item -LiteralPath (Join-Path $out $asset)
$manifest = [ordered]@{
    schema = 1; versionCode = $code; versionName = $name
    url = "https://github.com/$Repo/releases/download/$tag/$asset"
    sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    size = $file.Length; notes = $Notes
}
$manifestPath = Join-Path $out 'update-manifest.json'
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
Write-Output "Выпуск $tag (versionCode $code): $out"
if ($Publish) {
    $body = if ($Notes) { $Notes } else { "Pocket Android $name" }
    & gh release create $tag $file.FullName $manifestPath -R $Repo --title "Pocket Android $name" --notes $body --target main
    if ($LASTEXITCODE -ne 0) { throw 'GitHub не принял выпуск.' }
    Write-Output "Опубликовано: https://github.com/$Repo/releases/tag/$tag"
}
