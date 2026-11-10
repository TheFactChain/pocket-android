param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9:_-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$ProfilePath
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1')
$taskSource=(Resolve-Path -LiteralPath $ProfilePath).Path
$taskBytes=[IO.File]::ReadAllBytes($taskSource)
if($taskBytes.Length -eq 0 -or $taskBytes.Length -gt 1048576){throw 'Неверный размер локального подключения.'}
$taskPackage='io.github.nekontritce11123.pocketandroid'
$taskStart=[Diagnostics.ProcessStartInfo]::new()
$taskStart.FileName=Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$taskStart.UseShellExecute=$false
$taskStart.CreateNoWindow=$true
$taskStart.RedirectStandardInput=$true
$taskStart.RedirectStandardOutput=$true
$taskStart.RedirectStandardError=$true
foreach($taskArgument in @('-s',$Serial,'shell','-T',
    "run-as $taskPackage --user 0 sh -c 'umask 077; mkdir -p files; cat > files/.pending-test-profile.tmp && mv files/.pending-test-profile.tmp files/pending-test-profile.json'")){
    $taskStart.ArgumentList.Add($taskArgument)
}
$taskProcess=[Diagnostics.Process]::new();$taskProcess.StartInfo=$taskStart
try {
    [void]$taskProcess.Start()
    $taskOut=$taskProcess.StandardOutput.ReadToEndAsync()
    $taskError=$taskProcess.StandardError.ReadToEndAsync()
    $taskProcess.StandardInput.BaseStream.Write($taskBytes,0,$taskBytes.Length)
    $taskProcess.StandardInput.Close()
    if(-not $taskProcess.WaitForExit(15000)){$taskProcess.Kill();throw 'Телефон не завершил приватную передачу.'}
    # Never print subprocess output: a malformed remote shell must not echo credentials into logs.
    $taskUnexpectedOutput=$taskOut.GetAwaiter().GetResult()
    [void]$taskError.GetAwaiter().GetResult()
    if($taskProcess.ExitCode -ne 0 -or $taskUnexpectedOutput.Length -gt 0){throw 'Приватная передача не завершена. Нужна подключённая debug-установка Pocket.'}
    Write-Output 'Подключение передано в приватные данные. Pocket зашифрует его в рабочем профиле и удалит промежуточный файл.'
} finally {
    [Array]::Clear($taskBytes,0,$taskBytes.Length)
    $taskProcess.Dispose()
}
