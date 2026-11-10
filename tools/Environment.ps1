$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
$taskTools = Join-Path $taskRoot '.tools'
$taskProperties = Join-Path $taskRoot 'local.properties'
if (-not (Test-Path -LiteralPath $taskProperties)) { throw 'Сначала выполни tools/Setup.ps1 -AndroidSdk <путь к SDK>.' }
$taskSdkLine = Get-Content -LiteralPath $taskProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $taskSdkLine) { throw 'В local.properties отсутствует sdk.dir.' }
$env:ANDROID_HOME = $taskSdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME ('ndk/' + $taskLock.android.ndk)
$env:JAVA_HOME = Join-Path $taskTools $taskLock.jdk.directory
$env:GOROOT = Join-Path $taskTools $taskLock.go.directory
$env:GOPATH = Join-Path $taskTools 'go-path'
$env:GOBIN = Join-Path $taskTools 'bin'
$env:GOCACHE = Join-Path $taskTools 'go-cache'
$env:GOMODCACHE = Join-Path $taskTools 'go-mod'
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-home'
$env:PATH = @((Join-Path $env:JAVA_HOME 'bin'), (Join-Path $env:GOROOT 'bin'), $env:GOBIN,
    (Join-Path $env:ANDROID_HOME 'platform-tools'), $env:PATH) -join [IO.Path]::PathSeparator
# This new repository was explicitly selected; Git trust stays in this process.
$taskGitIndex = if ($env:GIT_CONFIG_COUNT) { [int]$env:GIT_CONFIG_COUNT } else { 0 }
[Environment]::SetEnvironmentVariable("GIT_CONFIG_KEY_$taskGitIndex", 'safe.directory', 'Process')
[Environment]::SetEnvironmentVariable("GIT_CONFIG_VALUE_$taskGitIndex", $taskRoot.Replace('\','/'), 'Process')
$env:GIT_CONFIG_COUNT = [string]($taskGitIndex + 1)
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'Локальная Java отсутствует. Выполни tools/Setup.ps1.' }
