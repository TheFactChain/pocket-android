$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskLicenses = Join-Path $taskRoot 'docs/licenses'
$taskAndroid = Join-Path $taskLicenses 'android'
$taskCache = Join-Path $taskRoot '.tools/gradle-home/caches/modules-2/files-2.1'
$taskModules = @(Get-Content (Join-Path $taskAndroid 'release-runtime-dependencies.json') -Raw | ConvertFrom-Json)
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item -LiteralPath (Join-Path $taskRoot 'libs/SetupWizardLibrary/library/LICENSE') -Destination (Join-Path $taskAndroid 'APACHE-2.0.txt') -Force
$taskAttributions = [Text.StringBuilder]::new()
[void]$taskAttributions.AppendLine('Android releaseRuntimeClasspath: coordinates and original Maven attribution metadata.')
[void]$taskAttributions.AppendLine('The full Apache 2.0 text follows in android/APACHE-2.0.txt. Metadata-only modules are retained conservatively.')
$taskArchiveNotices = [Collections.Generic.List[string]]::new()

function Save-ArchiveNotices([IO.Stream]$Stream, [string]$Coordinate, [string]$Prefix, [int]$Depth) {
    $taskZip = [IO.Compression.ZipArchive]::new($Stream, [IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        foreach ($taskEntry in $taskZip.Entries) {
            if ($taskEntry.FullName -match '(?i)(?:^|[/_.-])(?:licen[sc]e|notice|copying|copyright)(?:[._-]|$)' -and $taskEntry.Length -gt 0) {
                $taskName = ($Coordinate + '_' + $Prefix + $taskEntry.FullName) -replace '[^a-zA-Z0-9._-]', '_'
                $taskDestination = Join-Path $taskAndroid ('archive-notices/' + $taskName)
                New-Item -ItemType Directory -Force (Split-Path $taskDestination -Parent) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskDestination, $true)
                $taskArchiveNotices.Add($taskDestination)
            } elseif ($Depth -eq 0 -and $taskEntry.FullName -match '\.jar$') {
                $taskInput = $taskEntry.Open()
                $taskMemory = [IO.MemoryStream]::new()
                try {
                    $taskInput.CopyTo($taskMemory)
                    $taskMemory.Position = 0
                    Save-ArchiveNotices $taskMemory $Coordinate ($taskEntry.FullName + '_') 1
                } finally { $taskInput.Dispose(); $taskMemory.Dispose() }
            }
        }
    } finally { $taskZip.Dispose() }
}

foreach ($taskModule in $taskModules | Sort-Object { $_.group }, { $_.name }) {
    $taskCoordinate = "$($taskModule.group):$($taskModule.name):$($taskModule.version)"
    $taskDirectory = Join-Path $taskCache "$($taskModule.group)/$($taskModule.name)/$($taskModule.version)"
    $taskPom = Get-ChildItem -LiteralPath $taskDirectory -Recurse -File -Filter '*.pom' | Select-Object -First 1
    if (-not $taskPom) { throw "Missing cached POM: $taskCoordinate" }
    $taskPomText = Get-Content -LiteralPath $taskPom.FullName -Raw
    [xml]$taskXml = $taskPomText
    [void]$taskAttributions.AppendLine()
    [void]$taskAttributions.AppendLine($taskCoordinate)
    foreach ($taskNode in $taskXml.SelectNodes('//*[local-name()="developers"]/*[local-name()="developer"]')) {
        foreach ($taskField in @('name', 'organization', 'url', 'organizationUrl')) {
            $taskValue = $taskNode.SelectSingleNode("*[local-name()='$taskField']")
            if ($taskValue -and $taskValue.InnerText.Trim()) { [void]$taskAttributions.AppendLine($taskValue.InnerText.Trim()) }
        }
    }
    foreach ($taskComment in [regex]::Matches($taskPomText, '(?s)<!--.*?-->')) {
        if ($taskComment.Value -match '(?i)copyright') { [void]$taskAttributions.AppendLine($taskComment.Value) }
    }
    $taskLicenseNodes = $taskXml.SelectNodes('//*[local-name()="licenses"]/*[local-name()="license"]')
    if ($taskLicenseNodes.Count -eq 0) {
        $taskParent = $taskXml.SelectSingleNode('/*[local-name()="project"]/*[local-name()="parent"]')
        if ($taskParent) {
            $taskParentDirectory = Join-Path $taskCache "$($taskParent.groupId)/$($taskParent.artifactId)/$($taskParent.version)"
            $taskParentPom = Get-ChildItem -LiteralPath $taskParentDirectory -Recurse -File -Filter '*.pom' | Select-Object -First 1
            if ($taskParentPom) {
                [xml]$taskParentXml = Get-Content -LiteralPath $taskParentPom.FullName -Raw
                $taskLicenseNodes = $taskParentXml.SelectNodes('//*[local-name()="licenses"]/*[local-name()="license"]')
            }
        }
    }
    if ($taskLicenseNodes.Count -eq 0) { throw "Cannot establish Maven license: $taskCoordinate" }
    foreach ($taskNode in $taskLicenseNodes) {
        [void]$taskAttributions.AppendLine("$($taskNode.name): $($taskNode.url)")
        if ($taskNode.name -notmatch '(?i)apache') { throw "Uncovered Maven license: $taskCoordinate / $($taskNode.name)" }
    }
    foreach ($taskArchive in Get-ChildItem -LiteralPath $taskDirectory -Recurse -File | Where-Object { $_.Extension -in '.aar', '.jar' }) {
        $taskStream = [IO.File]::OpenRead($taskArchive.FullName)
        try { Save-ArchiveNotices $taskStream $taskCoordinate '' 0 } finally { $taskStream.Dispose() }
    }
}
[IO.File]::WriteAllText((Join-Path $taskAndroid 'ATTRIBUTIONS.txt'), $taskAttributions.ToString(), [Text.UTF8Encoding]::new($false))

$taskOutput = [Text.StringBuilder]::new()
function Append-Notice([string]$Label, [string]$Path) {
    [void]$taskOutput.AppendLine()
    [void]$taskOutput.AppendLine(('=' * 72))
    [void]$taskOutput.AppendLine($Label)
    [void]$taskOutput.AppendLine(('=' * 72))
    [void]$taskOutput.AppendLine([IO.File]::ReadAllText($Path).TrimEnd())
}
[void]$taskOutput.AppendLine('Pocket Android — происхождение, авторство и лицензии компонентов')
$taskOverview = [IO.File]::ReadAllText((Join-Path $taskRoot 'THIRD_PARTY_NOTICES.md'))
$taskOverview = [regex]::Replace($taskOverview, '\[([^\]]+)\]\(([^)]+)\)', '$1 ($2)')
$taskOverview = [regex]::Replace($taskOverview, '(?m)^#+\s*', '').Replace('`', '')
[void]$taskOutput.AppendLine($taskOverview.TrimEnd())
$taskGoInventory = Get-Content (Join-Path $taskLicenses 'libbox-dependencies.json') -Raw | ConvertFrom-Json
[void]$taskOutput.AppendLine()
[void]$taskOutput.AppendLine("libbox AAR SHA256: $($taskGoInventory.aarSHA256)")
foreach ($taskPatch in $taskGoInventory.sourcePatches) {
    [void]$taskOutput.AppendLine('Pocket modifies upstream command_server.go/report.go to disable plaintext configuration snapshots and report copies (2026-10-29).')
    [void]$taskOutput.AppendLine("Corresponding Source patch: $($taskPatch.path); SHA256: $($taskPatch.sha256)")
}
Append-Notice 'Shelter / Pocket — GNU GPL version 3' (Join-Path $taskRoot 'LICENSE')
Append-Notice 'SetupWizardLibrary — original LICENSE' (Join-Path $taskRoot 'libs/SetupWizardLibrary/library/LICENSE')
$taskSetupWizardSource = [IO.File]::ReadAllText((Join-Path $taskRoot 'libs/SetupWizardLibrary/library/main/src/com/android/setupwizardlib/SetupWizardLayout.java'))
$taskSetupWizardNotice = [regex]::Match($taskSetupWizardSource, '\A/\*.*?\*/', [Text.RegularExpressions.RegexOptions]::Singleline).Value
if ($taskSetupWizardNotice -notmatch 'Copyright') { throw 'SetupWizardLibrary original attribution is missing' }
[void]$taskOutput.AppendLine()
[void]$taskOutput.AppendLine('SetupWizardLibrary — original source attribution')
[void]$taskOutput.AppendLine($taskSetupWizardNotice)
Append-Notice 'Android release runtime dependencies — original Maven attributions' (Join-Path $taskAndroid 'ATTRIBUTIONS.txt')
$taskLabels = @{}
foreach ($taskModule in $taskGoInventory.modules) {
    foreach ($taskFile in $taskModule.licenseFiles) { $taskLabels[$taskFile] = "$($taskModule.module)@$($taskModule.version) — $taskFile" }
}
$taskIncluded = @()
foreach ($taskFile in Get-ChildItem -LiteralPath $taskLicenses -Recurse -File | Sort-Object FullName) {
    $taskRelative = [IO.Path]::GetRelativePath($taskLicenses, $taskFile.FullName).Replace('\', '/')
    $taskIsLicense = $taskFile.Name -match '^(?i:licen[sc]e|copying|notice|copyright|authors|patents)(?:[._-].*)?$'
    $taskIsQR = $taskRelative -match '^qr/.+-(?:COPYING|LICENSE|NOTICE)$'
    if ($taskIsLicense -or $taskIsQR -or $taskRelative -eq 'android/APACHE-2.0.txt' -or $taskFile.FullName -in $taskArchiveNotices) {
        $taskLabel = if ($taskLabels.ContainsKey($taskRelative)) { $taskLabels[$taskRelative] } else { $taskRelative }
        Append-Notice $taskLabel $taskFile.FullName
        $taskIncluded += $taskRelative
    }
}
$taskText = $taskOutput.ToString().Replace("`r`n", "`n")
if ($taskText -match '(?i)\b[A-Z]:[\\/]|/Users/|/home/|BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|(?:vless|hysteria2|hy2)://') {
    throw 'Notices contain a local path or private connection material; refusing to write the asset.'
}
if ($taskText.Contains([Environment]::UserName + '\') -or $taskText.Contains([Environment]::UserName + '/')) {
    throw 'Notices contain a local user path.'
}
$taskAsset = Join-Path $taskRoot 'app/src/main/assets/THIRD_PARTY_NOTICES.txt'
New-Item -ItemType Directory -Force (Split-Path $taskAsset -Parent) | Out-Null
[IO.File]::WriteAllText($taskAsset, $taskText, [Text.UTF8Encoding]::new($false))
Write-Output "Asset generated: $([Text.Encoding]::UTF8.GetByteCount($taskText)) bytes; $($taskModules.Count) Maven coordinates; $($taskIncluded.Count) original license/notice files."
