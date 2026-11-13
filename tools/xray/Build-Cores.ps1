# Draft: one gomobile AAR with sing-box libbox + the Xray wrapper. Not wired into Build.ps1.
# Needs .tools/sing-box already patched (run tools/Build-Libbox.ps1 -VerifySourceOnly first).
param([string]$Targets = 'android/arm64,android/arm')
$ErrorActionPreference = 'Stop'
$taskOriginalRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$taskXrayVersion = 'v1.260327.0'   # first with finalmask tcp fragment, which providers' configs use; needs Go 1.26
$taskTags = 'with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0'
$taskLinkerFlags = '-X github.com/sagernet/sing-box/constant.Version=1.14.4 -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid='
$taskDriveLetter = [char[]](82..90) | Where-Object { -not (Test-Path -LiteralPath "${_}:\") } | Select-Object -First 1
if (-not $taskDriveLetter) { throw 'Нужен один свободный диск R-Z.' }
$taskDrive = "${taskDriveLetter}:"
$taskEnvironment = @{}
Get-ChildItem Env: | ForEach-Object { $taskEnvironment[$_.Name] = $_.Value }
& subst.exe $taskDrive $taskOriginalRoot
if ($LASTEXITCODE -ne 0) { throw 'subst failed.' }
Push-Location "$taskDrive\"
try {
    . "$taskDrive\tools/Environment.ps1"
    $env:GOFLAGS = '-mod=mod'; $env:GOTOOLCHAIN = 'go1.26.11'
    $taskWork = "$taskDrive\.tools\pocket-cores"
    New-Item -ItemType Directory -Force "$taskWork\pocketxray", "$taskWork\out" | Out-Null
    Copy-Item "$taskDrive\tools\xray\pocketxray\*.go" "$taskWork\pocketxray\" -Force
    # sing-box is taken from the patched local checkout via replace; nothing in it is modified.
    Set-Content "$taskWork\go.mod" -Encoding ascii -Value @"
module pocketcores

go 1.26

require (
	github.com/sagernet/sing-box v0.0.0
	github.com/xtls/xray-core $taskXrayVersion
)

replace github.com/sagernet/sing-box => ../sing-box
"@
    Set-Content "$taskWork\tools.go" -Encoding ascii -Value "//go:build tools`r`n`r`npackage tools`r`n`r`nimport _ `"github.com/sagernet/sing-box/experimental/libbox`""
    # Windows PowerShell 5.1: native stderr under Stop is fatal; also drop the '=X:' variables gomobile cannot parse.
    function Invoke-Native($exe, $arguments, $dir, $log) {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $exe; $psi.Arguments = $arguments; $psi.WorkingDirectory = $dir
        $psi.UseShellExecute = $false; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
        @($psi.Environment.Keys | Where-Object { $_.StartsWith('=') }) | ForEach-Object { [void]$psi.Environment.Remove($_) }
        $p = [Diagnostics.Process]::Start($psi)
        $e = $p.StandardError.ReadToEndAsync(); $o = $p.StandardOutput.ReadToEnd(); $p.WaitForExit()
        Add-Content $log ($o + $e.Result)
        if ($p.ExitCode -ne 0) { throw "$exe завершился с кодом $($p.ExitCode); см. $log" }
    }
    $taskLog = "$taskWork\out\build.log"; Set-Content $taskLog ''
    Invoke-Native "$env:GOROOT\bin\go.exe" 'mod tidy' $taskWork $taskLog
    Invoke-Native "$env:GOBIN\gomobile.exe" ("bind -v -o out\pocket-cores.aar -target $Targets -androidapi 29 -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false -ldflags `"$taskLinkerFlags`" -tags $taskTags github.com/sagernet/sing-box/experimental/libbox pocketcores/pocketxray") $taskWork $taskLog
    Write-Host "Готово: .tools/pocket-cores/out/pocket-cores.aar"
} finally {
    Pop-Location
    & subst.exe $taskDrive /D
    Get-ChildItem Env: | Where-Object { -not $taskEnvironment.ContainsKey($_.Name) } | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    foreach ($taskEntry in $taskEnvironment.GetEnumerator()) { [Environment]::SetEnvironmentVariable($taskEntry.Key, $taskEntry.Value, 'Process') }
}
