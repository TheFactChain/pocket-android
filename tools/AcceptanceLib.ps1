# Оценка прогона стенда. Без телефона и сети: только разбор записей Probe и наблюдателя.
# Сырые IP сюда не попадают: Probe пишет хэш SHA-256(salt:ip), стенд сравнивает хэши.

function Get-ExitHash([string]$Salt, [string]$Ip) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $bytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes("${Salt}:$($Ip.Trim())")) } finally { $sha.Dispose() }
    -join ($bytes[0..7] | ForEach-Object { $_.ToString('x2') })
}

# Записи Probe одного прогона (по метке соли), время переведено в часы ПК.
function ConvertFrom-ProbeEvidence([string[]]$Lines, [string]$Tag, [long]$PhoneOffsetMs) {
    foreach ($line in $Lines) {
        if (-not $line.Trim()) { continue }
        try { $r = $line | ConvertFrom-Json } catch { continue }
        if ($r.tag -ne $Tag) { continue }
        [pscustomobject]@{ t = [long]$r.t - $PhoneOffsetMs; uid = [int]$r.uid; work = ([int]$r.uid -ge 1000000)
            http = [string]$r.http; exit = [string]$r.exit; leak = [string]$r.leak; udp = [string]$r.udp; dns = [string]$r.dns }
    }
}

# Наблюдатель на ПК: любой помеченный запрос — это прямой выход в локальную сеть.
function ConvertFrom-ObserverEvidence([string[]]$Lines) {
    foreach ($line in $Lines) {
        if (-not $line.Trim()) { continue }
        try { $o = $line | ConvertFrom-Json } catch { continue }
        if ($o.kind -notin 'tcp', 'udp') { continue }
        if ($o.marker -notmatch 'uid=?(\d+)') { continue }
        [pscustomobject]@{ t = ([DateTimeOffset][DateTime]::Parse($o.time).ToUniversalTime()).ToUnixTimeMilliseconds()
            kind = $o.kind; uid = [int]$Matches[1] }
    }
}

# Ожидаемый выход Pocket: самый частый хэш рабочих успешных проверок.
function Get-BaselineHash($Records) {
    $top = $Records | Where-Object { $_.work -and $_.http -eq '200' -and $_.exit } |
        Group-Object exit | Sort-Object Count -Descending | Select-Object -First 1
    if ($top -and $top.Count -ge 5) { $top.Name } else { $null }
}

function Get-ExitClass($Record, [string]$Baseline, [string[]]$DirectHashes) {
    # Ответ 200 без IP (например, страница авторизации сети) — запрос куда-то дошёл, это не блокировка.
    if (-not $Record.exit -and $Record.http -eq '200') { 'unverified' }
    elseif (-not $Record.exit) { 'none' }
    elseif ($Record.exit -eq $Baseline) { 'tunnel' }
    elseif ($DirectHashes -contains $Record.exit) { 'direct' }
    else { 'other' }
}

# Вердикт сценария. FaultEnd = момент возврата условий (сеть включена, обновление установлено, телефон разблокирован).
function Get-ScenarioVerdict {
    param([string]$Name, $Records, $Observations, [long]$Start, [long]$End, [long]$FaultEnd = 0,
          [string]$Baseline, [string[]]$DirectHashes, [int]$RecoveryLimitSec = 120)
    $work = @($Records | Where-Object { $_.work -and $_.t -ge $Start -and $_.t -le $End } | Sort-Object t)
    $classes = @($work | ForEach-Object { Get-ExitClass $_ $Baseline $DirectHashes })
    $direct = @($classes | Where-Object { $_ -eq 'direct' }).Count
    $other = @($classes | Where-Object { $_ -eq 'other' }).Count
    $unverified = @($classes | Where-Object { $_ -eq 'unverified' }).Count
    # Сбой без единой записи рабочего Probe ничего не доказывает: Probe мог быть заморожен.
    $faultCovered = (-not $FaultEnd) -or @($work | Where-Object { $_.t -le $FaultEnd }).Count -gt 0
    $lan = @($Observations | Where-Object { $_.uid -ge 1000000 -and $_.t -ge $Start -and $_.t -le $End }).Count
    $lan += @($work | Where-Object { $_.leak -eq '200' }).Count
    $recovery = $null
    if ($FaultEnd) {
        $first = $work | Where-Object { $_.t -ge $FaultEnd -and (Get-ExitClass $_ $Baseline $DirectHashes) -eq 'tunnel' } | Select-Object -First 1
        if ($first) { $recovery = [math]::Round(($first.t - $FaultEnd) / 1000.0, 1) }
    }
    $problems = @()
    if (-not $Baseline) { $problems += 'нет подтверждённого выхода Pocket' }
    elseif ($DirectHashes -contains $Baseline) { $problems += 'выход Pocket совпадает с прямым IP' }
    if ($direct) { $problems += "прямой выход в интернет: $direct" }
    if ($lan) { $problems += "прямой выход в локальную сеть: $lan" }
    if ($other) { $problems += "чужой внешний IP: $other" }
    if ($unverified) { $problems += "ответ 200 без IP (запрос ушёл не туда): $unverified" }
    if ($FaultEnd -and $work.Count -and $faultCovered -and ($null -eq $recovery -or $recovery -gt $RecoveryLimitSec)) { $problems += "нет восстановления за $RecoveryLimitSec с" }
    $status = if ($problems) { 'FAIL' } elseif ($work.Count -eq 0 -or -not $faultCovered) { 'INCONCLUSIVE' } else { 'PASS' }
    if ($work.Count -eq 0) { $problems += 'нет записей рабочего Probe' }
    elseif (-not $faultCovered) { $problems += 'во время сбоя рабочий Probe не сделал ни одной проверки' }
    [ordered]@{ name = $Name; status = $status; checks = $work.Count
        tunnel = @($classes | Where-Object { $_ -eq 'tunnel' }).Count; blocked = @($classes | Where-Object { $_ -eq 'none' }).Count
        directInternet = $direct; directLan = $lan; foreignIp = $other; recoverySec = $recovery; problems = $problems }
}

# Положительный контроль: незащищённый личный Probe обязан быть замечен детекторами, иначе стенду нельзя верить.
function Get-ControlVerdict($Records, $Observations, [string]$PcHash, [string]$Baseline) {
    $personal = @($Records | Where-Object { -not $_.work -and $_.http -eq '200' -and $_.exit })
    $exitSeen = @($personal | Where-Object { $_.exit -eq $PcHash }).Count
    $lanSeen = @($Observations | Where-Object { $_.uid -lt 1000000 }).Count
    $problems = @(); $notes = @()
    if (-not $personal.Count) { $problems += 'личный Probe не дал ни одного ответа' }
    elseif (@($personal | Where-Object { $_.exit -eq $Baseline }).Count) { $problems += 'личный выход совпал с выходом Pocket: обход Pocket нельзя отличить' }
    if ($lanSeen -eq 0) { $problems += 'наблюдатель на ПК не получил контрольных запросов (брандмауэр или другая сеть)' }
    # Личный VPN телефона меняет «прямой» выход; тогда сравниваем и с IP компьютера, и с личным выходом.
    if ($personal.Count -and $exitSeen -eq 0) { $notes += 'личный профиль выходит не с IP компьютера (свой VPN): прямой IP проверяется по ПК' }
    [ordered]@{ name = 'control'; status = $(if ($problems) { 'INCONCLUSIVE' } else { 'PASS' })
        personalChecks = $personal.Count; personalDirect = $exitSeen; observerHits = $lanSeen; problems = $problems; notes = $notes }
}
