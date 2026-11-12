# Стенд доказательств Pocket: прогоняет сценарии на подключённом телефоне и пишет отчёт.
# Probe в профиле Pocket и личный Probe ходят на api.ipify.org (хэш внешнего IP с солью прогона)
# и на наблюдатель на этом ПК (любой рабочий запрос туда — прямой выход). Сырые IP в отчёт не попадают.
param(
    [string]$Serial,
    [ValidateSet('network', 'airplane', 'update', 'reboot')][string[]]$Scenarios = @('network', 'airplane', 'update'),
    [string]$ApkPath,
    [ValidateRange(5, 300)][int]$FaultSec = 30,
    [ValidateRange(10, 600)][int]$RecoveryLimitSec = 120,
    [ValidateRange(20, 600)][int]$BaselineSec = 45,
    [ValidateRange(1024, 65535)][int]$Port = 55887,
    # Оболочка наблюдателя: ей нужен входящий доступ в брандмауэре Windows для частной сети.
    [string]$ObserverShell = 'powershell'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Environment.ps1') | Out-Null
. (Join-Path $PSScriptRoot 'AcceptanceLib.ps1')
$root = Split-Path $PSScriptRoot -Parent
$adbExe = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$pocket = 'io.github.nekontritce11123.pocketandroid'
$probe = 'io.github.nekontritce11123.pocketandroid.probe'
if (-not $ApkPath) { $ApkPath = Join-Path $root 'app/build/outputs/apk/release/app-release.apk' }

function Adb {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $adbExe -s $Serial @args 2>$null; if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' '): код $LASTEXITCODE" }; $out }
    finally { $ErrorActionPreference = $previous }
}
function Sh([string]$Command) { Adb shell $Command }
function NowMs { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
function Say([string]$Text) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Text) }
function RunAs([int]$User, [string]$Command) { Sh "run-as $probe --user $User sh -c '$Command'" }
function Focus { (Sh 'dumpsys window | grep mCurrentFocus; true') -join ' ' }

function Write-ProbeTarget([int]$User, [string]$Text) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($Text))
    RunAs $User "umask 077; rm -f files/checks.jsonl; echo $b64 | base64 -d > files/t.tmp && mv files/t.tmp files/test-target.properties" | Out-Null
}
function Read-ProbeEvidence([int]$User, [int]$Tail = 0) {
    $cmd = if ($Tail) { "tail -n $Tail files/checks.jsonl 2>/dev/null; true" } else { 'cat files/checks.jsonl 2>/dev/null; true' }
    @(RunAs $User $cmd)
}

# Рабочий Probe запускается только через Pocket, как у пользователя: shell не может запускать приложения в user 11.
function Start-WorkProbe {
    for ($try = 1; $try -le 3; $try++) {
        Sh "am start --user 0 -n $pocket/net.typeblog.shelter.ui.MainActivity" | Out-Null
        Start-Sleep 3
        if ((Focus) -notmatch [regex]::Escape($pocket)) { Sh 'input keyevent KEYCODE_BACK' | Out-Null; continue }
        Sh 'uiautomator dump /sdcard/pocket-stand.xml >/dev/null 2>&1; true' | Out-Null
        $xml = [xml]((Sh 'cat /sdcard/pocket-stand.xml; rm -f /sdcard/pocket-stand.xml') -join "`n")
        $tile = $xml.SelectNodes('//node') | Where-Object { $_.'content-desc' -eq 'Pocket Probe' -and $_.clickable -eq 'true' } | Select-Object -First 1
        if (-not $tile) { Sh 'input keyevent KEYCODE_BACK' | Out-Null; continue }
        if ((Focus) -notmatch [regex]::Escape($pocket)) { continue }
        $b = [regex]::Matches($tile.bounds, '\d+') | ForEach-Object { [int]$_.Value }
        Sh "input tap $([int](($b[0] + $b[2]) / 2)) $([int](($b[1] + $b[3]) / 2))" | Out-Null
        return
    }
    throw 'Не удалось открыть Pocket Probe из Pocket: нет плитки «Pocket Probe» или экран занят.'
}
function Start-Probes {
    Sh "am start --user 0 -n $probe/.MainActivity" | Out-Null
    Start-Sleep 2
    Start-WorkProbe
}

# Ждём первую рабочую проверку с ожидаемым выходом после момента $After.
function Wait-Recovery([long]$After, [string]$Baseline) {
    $deadline = (NowMs) + $RecoveryLimitSec * 1000 + 10000
    while ((NowMs) -lt $deadline) {
        Start-Sleep 4
        try { $recent = @(ConvertFrom-ProbeEvidence (Read-ProbeEvidence 11 6) $tag $offset) } catch { continue }
        if ($recent | Where-Object { $_.t -ge $After -and $_.exit -eq $Baseline }) { return $true }
    }
    $false
}
function Get-NetworkBackSec($Records, [long]$After) {
    $first = $Records | Where-Object { -not $_.work -and $_.http -eq '200' -and $_.t -ge $After } | Sort-Object t | Select-Object -First 1
    if ($first) { [math]::Round(($first.t - $After) / 1000.0, 1) } else { $null }
}

# ---------- подготовка ----------
if (-not $Serial) { $Serial = ((& $adbExe devices) | Select-String '^(\S+)\s+device$' | Select-Object -First 1).Matches.Groups[1].Value }
if (-not $Serial) { throw 'Телефон не найден в adb devices.' }
$runId = 'run-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$outDir = Join-Path $root ".artifacts/acceptance/$runId"
New-Item -ItemType Directory -Force $outDir | Out-Null
$saltBytes = [byte[]]::new(16); [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($saltBytes)
$salt = -join ($saltBytes | ForEach-Object { $_.ToString('x2') })
$tag = Get-ExitHash $salt '0.0.0.0'

$pocketInfo = (Sh "dumpsys package $pocket | grep -E 'versionCode|versionName|User (0|11):'") -join "`n"
if ($pocketInfo -notmatch 'versionCode=(\d+)') { throw 'Pocket не установлен.' }
$versionCode = [int]$Matches[1]; $versionName = if ($pocketInfo -match 'versionName=(\S+)') { $Matches[1] } else { '' }
$owner = (Sh "dumpsys device_policy | grep -A2 'Profile Owner (User 11)'") -join ' '
if ($owner -notmatch [regex]::Escape($pocket)) { throw 'Профиль Pocket (user 11) не найден или владелец не Pocket.' }
$probeInfo = (Sh "dumpsys package $probe | grep -E 'User (0|11):'") -join "`n"
if ($probeInfo -notmatch 'User 0:[^\n]*installed=true' -or $probeInfo -notmatch 'User 11:[^\n]*installed=true') { throw 'Pocket Probe должен быть в личном профиле и скопирован в Pocket.' }
if ((Sh "dumpsys trust | grep 'id=0'; true") -join ' ' -notmatch 'deviceLocked=0') { throw 'Разблокируй телефон и запусти стенд снова.' }
$mobileData = ((Sh 'settings get global mobile_data') -join '').Trim()
# Исходное состояние сети, чтобы вернуть именно его, а не включить то, что было выключено.
$wifiWasOn = ((Sh 'settings get global wifi_on') -join '').Trim() -ne '0'
$airplaneWasOn = ((Sh 'settings get global airplane_mode_on') -join '').Trim() -eq '1'
if ($airplaneWasOn) { throw 'Выключи режим полёта: стенду нужна сеть.' }

# Адрес ПК в той же сети, что Wi-Fi телефона: на ПК может быть свой VPN с маршрутом по умолчанию.
$phoneWifi = ((Sh 'ip -4 -o addr show wlan0') -join ' ') -replace '.*inet (\d+\.\d+\.\d+)\.\d+.*', '$1'
$pcLan = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -like "$phoneWifi.*" } | Select-Object -First 1).IPAddress
if (-not $pcLan) { throw 'ПК и телефон не в одной сети Wi-Fi/LAN: наблюдателю некуда принимать запросы.' }
# Прямой IP провайдера: запрос с привязкой к LAN-адресу, мимо VPN самого ПК.
$pcIp = (& curl.exe -s --max-time 15 --interface $pcLan https://api.ipify.org) -join ''
if ($pcIp -notmatch '^[0-9a-fA-F:.]{2,45}$') { throw 'Не удалось узнать прямой IP провайдера с ПК.' }
$pcHash = Get-ExitHash $salt $pcIp; $pcIp = $null
$offset = [long](((Sh 'date +%s') -join '').Trim()) * 1000 - (NowMs)
$apkSha = if (Test-Path $ApkPath) { (Get-FileHash $ApkPath -Algorithm SHA256).Hash.ToLower() } else { '' }

$target = "url=https://api.ipify.org`nresolveHost=example.com`ninterval=2`nudpHost=$pcLan`nudpPort=$Port`nrepeat=true`ndurationMinutes=30`nleakUrl=http://${pcLan}:$Port/pocket-test/$runId`nsalt=$salt`n"
$events = [ordered]@{}
$observer = $null
$results = @()
Say "Стенд ${runId}: Pocket $versionName ($versionCode). Стенд будет управлять телефоном; не трогай его до конца."
try {
    # Start-Process в PowerShell 5.1 не берёт пути с пробелами в кавычки сам.
    $observer = Start-Process $ObserverShell -WindowStyle Hidden -PassThru -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "{0}" -ListenAddress {1} -Port {2} -Seconds 3600 -EvidencePath "{3}"' -f
        (Join-Path $PSScriptRoot 'Observe-Network.ps1'), $pcLan, $Port, (Join-Path $outDir 'observer.jsonl'))
    Start-Sleep 2
    if ($observer.HasExited) { throw 'Наблюдатель на ПК не запустился (порт занят?).' }
    foreach ($user in 0, 11) { Write-ProbeTarget $user $target }
    Start-Probes

    # ---------- база ----------
    $events.baselineStart = NowMs
    Say "База: $BaselineSec с обычной работы."
    Start-Sleep $BaselineSec
    $events.baselineEnd = NowMs
    $early = @(ConvertFrom-ProbeEvidence (Read-ProbeEvidence 11) $tag $offset)
    $baseline = Get-BaselineHash $early
    if (-not $baseline) { throw 'Рабочий Probe не получил стабильный внешний IP за время базы: Pocket не подключён или Probe не запустился.' }

    foreach ($scenario in $Scenarios) {
        $start = NowMs; $faultEnd = 0; $extra = [ordered]@{}
        switch ($scenario) {
            'network' {
                Say "Сеть: Wi-Fi выключен на $FaultSec с."
                Sh 'svc wifi disable' | Out-Null; Start-Sleep $FaultSec; Sh 'svc wifi enable' | Out-Null
                $faultEnd = NowMs
            }
            'airplane' {
                Say "Режим полёта на $FaultSec с."
                Sh 'cmd connectivity airplane-mode enable' | Out-Null; Start-Sleep $FaultSec; Sh 'cmd connectivity airplane-mode disable' | Out-Null
                $faultEnd = NowMs
            }
            'update' {
                if (-not $apkSha) { throw "Нет APK для обновления: $ApkPath" }
                $before = (Sh "dumpsys package $pocket | grep -E 'User (0|11):'") -replace '.*(ceDataInode=\d+).*', '$1'
                Say 'Обновление Pocket поверх установленного.'
                $asciiApk = Join-Path $env:TEMP 'pocket-stand-update.apk'  # adb надёжнее с ASCII-путём
                Copy-Item -LiteralPath $ApkPath $asciiApk -Force
                try { Adb install -r $asciiApk | Out-Null } finally { Remove-Item $asciiApk -ErrorAction SilentlyContinue }
                $faultEnd = NowMs
                $after = (Sh "dumpsys package $pocket | grep -E 'User (0|11):'") -replace '.*(ceDataInode=\d+).*', '$1'
                $extra.dataKept = (($before -join ',') -eq ($after -join ','))
                $extra.ownerKept = ((Sh "dumpsys device_policy | grep -A2 'Profile Owner (User 11)'") -join ' ') -match [regex]::Escape($pocket)
            }
            'reboot' {
                Say 'Перезагрузка. После включения РАЗБЛОКИРУЙ телефон — это единственное ручное действие.'
                Adb reboot | Out-Null
                Start-Sleep 10; Adb wait-for-device | Out-Null
                $bootUntil = (NowMs) + 300000
                do { Start-Sleep 2; try { $booted = ((Sh 'getprop sys.boot_completed') -join '').Trim() -eq '1' } catch { $booted = $false } } until ($booted -or (NowMs) -gt $bootUntil)
                $waitUntil = (NowMs) + 600000
                while ((NowMs) -lt $waitUntil -and ((Sh "dumpsys trust | grep 'id=0'; true") -join ' ') -notmatch 'deviceLocked=0') { Start-Sleep 2 }
                $faultEnd = NowMs
                # Часы телефона после перезагрузки сверяем, но записи до неё считаем по исходному смещению.
                $extra.clockShiftMs = [long](((Sh 'date +%s') -join '').Trim()) * 1000 - (NowMs) - $offset
                $extra.manualActions = 1
                Start-Sleep 5; Start-Probes
            }
        }
        $recovered = Wait-Recovery $faultEnd $baseline
        Start-Sleep 10  # окно после восстановления: выход должен остаться прежним
        $events[$scenario] = @{ start = $start; faultEnd = $faultEnd; end = (NowMs); recovered = $recovered; extra = $extra }
        Say "${scenario}: $(if ($recovered) { 'связь вернулась' } else { 'связь не вернулась за лимит' })"
    }
} catch {
    $fatal = $_.Exception.Message
    Say "Прогон прерван: $fatal"
} finally {
    # Возвращаем телефон в исходное состояние при любом исходе.
    try { Sh 'cmd connectivity airplane-mode disable' | Out-Null; if ($wifiWasOn) { Sh 'svc wifi enable' | Out-Null } } catch { Say "Проверь Wi-Fi и режим полёта вручную: $_" }
    Start-Sleep 3
    $raw = @{}
    foreach ($user in 0, 11) {
        try { $raw[$user] = @(Read-ProbeEvidence $user) } catch { $raw[$user] = @() }
        try { RunAs $user 'rm -f files/test-target.properties' | Out-Null } catch { Say "Не удалось убрать настройки Probe user $user" }
    }
    if ($observer -and -not $observer.HasExited) { Stop-Process -Id $observer.Id -Force }
}

# ---------- отчёт ----------
$raw[11] | Set-Content (Join-Path $outDir 'probe-work.jsonl') -Encoding utf8
$raw[0] | Set-Content (Join-Path $outDir 'probe-personal.jsonl') -Encoding utf8
$records = @(ConvertFrom-ProbeEvidence ($raw[0] + $raw[11]) $tag $offset)
$obsFile = Join-Path $outDir 'observer.jsonl'
$observations = @(if (Test-Path $obsFile) { ConvertFrom-ObserverEvidence (Get-Content $obsFile -Encoding UTF8) })
$personalHashes = @($records | Where-Object { -not $_.work -and $_.exit } | Select-Object -ExpandProperty exit -Unique)
$direct = @($pcHash) + $personalHashes | Select-Object -Unique

$results += Get-ControlVerdict $records $observations $pcHash $baseline
$results += Get-ScenarioVerdict 'baseline' $records $observations $events.baselineStart $events.baselineEnd 0 $baseline $direct $RecoveryLimitSec
foreach ($scenario in $Scenarios) {
    $e = $events[$scenario]
    if (-not $e) { $results += [ordered]@{ name = $scenario; status = 'INCONCLUSIVE'; problems = @('сценарий прерван') }; continue }
    $v = Get-ScenarioVerdict $scenario $records $observations $e.start $e.end $e.faultEnd $baseline $direct $RecoveryLimitSec
    $v.personalBackSec = Get-NetworkBackSec $records $e.faultEnd
    foreach ($k in $e.extra.Keys) { $v[$k] = $e.extra[$k] }
    if ($e.extra.Contains('dataKept') -and -not ($e.extra.dataKept -and $e.extra.ownerKept)) { $v.status = 'FAIL'; $v.problems += 'после обновления изменились данные или владелец профиля' }
    $results += $v
}

$notCovered = @('отказ и возврат сервера (нужно переключение на заведомо недоступное подключение)', 'пауза рабочего профиля', 'гибель процесса VPN', 'смена DNS/Private DNS, IPv6')
if ($mobileData -ne '1') { $notCovered += 'переход Wi-Fi <-> мобильная сеть (мобильные данные выключены)' }
if ($Scenarios -notcontains 'reboot') { $notCovered += 'перезагрузка (запусти с -Scenarios ...,reboot)' }
else { $notCovered += 'утечки во время загрузки до разблокировки: Probe запускается только после неё' }
$statuses = $results | ForEach-Object { $_.status }
$verdict = if ($statuses -contains 'FAIL') { 'FAIL' } elseif ($fatal -or $statuses -contains 'INCONCLUSIVE') { 'INCONCLUSIVE' } else { 'PASS' }
$report = [ordered]@{ run = $runId; verdict = $verdict; interrupted = $fatal; pocket = @{ versionName = $versionName; versionCode = $versionCode; apkSha256 = $apkSha }
    device = ((Sh 'getprop ro.product.model') -join '').Trim(); android = ((Sh 'getprop ro.build.version.release') -join '').Trim()
    settings = @{ faultSec = $FaultSec; recoveryLimitSec = $RecoveryLimitSec; baselineSec = $BaselineSec }
    scenarios = $results; notCovered = $notCovered }
$report | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outDir 'report.json') -Encoding utf8

$summary = @("Стенд Pocket $runId — итог: $verdict", "Pocket $versionName ($versionCode), $($report.device), Android $($report.android)", '')
if ($fatal) { $summary += "Прогон прерван: $fatal", '' }
foreach ($r in $results) {
    $line = "{0,-9} {1,-12}" -f $r.name, $r.status
    if ($r.Contains('checks')) { $line += " проверок $($r.checks), через Pocket $($r.tunnel), заблокировано $($r.blocked), утечек $($r.directInternet + $r.directLan), чужой IP $($r.foreignIp)" }
    if ($r.recoverySec -ne $null) { $line += ", восстановление $($r.recoverySec) с" }
    if ($r.personalBackSec -ne $null) { $line += " (личный профиль на связи через $($r.personalBackSec) с)" }
    $summary += $line
    foreach ($p in $r.problems) { $summary += "          - $p" }
    foreach ($n in $r.notes) { $summary += "          (заметка) $n" }
}
$summary += '', 'Не покрыто этим прогоном:'; $summary += $notCovered | ForEach-Object { "  - $_" }
$summary | Set-Content (Join-Path $outDir 'summary.txt') -Encoding utf8
$summary | ForEach-Object { Write-Output $_ }
Write-Output "Отчёт: $outDir"
if ($verdict -ne 'PASS') { exit 1 }
