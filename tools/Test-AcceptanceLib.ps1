# Самопроверка оценки стенда: синтетические записи, без телефона и сети.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AcceptanceLib.ps1')
function Assert($condition, $what) { if (-not $condition) { throw "FAIL: $what" } }

# Тот же вектор, что даёт ProbeChecks.exitHash в Java.
Assert ((Get-ExitHash '0123456789abcdef' '203.0.113.7') -eq '34256c0c0977f4e4') 'хэш совпадает с Java'
$salt = '0123456789abcdef'; $tag = Get-ExitHash $salt '0.0.0.0'
$vps = Get-ExitHash $salt '198.51.100.1'; $pc = Get-ExitHash $salt '203.0.113.7'; $foreign = Get-ExitHash $salt '192.0.2.9'

function Line($t, $uid, $http, $exit, $leak = 'SocketTimeoutException', $tg = $tag) {
    "{""t"":$t,""uid"":$uid,""run"":1,""seq"":1,""http"":""$http"",""exit"":""$exit"",""dns"":""ok"",""udp"":"""",""leak"":""$leak"",""tag"":""$tg""}"
}
$lines = @()
1..10 | ForEach-Object { $lines += Line (1000 + $_ * 1000) 1110372 200 $vps }            # норма
$lines += Line 12000 1110372 'ConnectException' ''                                       # сбой: блок
$lines += Line 20000 1110372 200 $vps                                                    # восстановление через 5 с
1..3 | ForEach-Object { $lines += Line (1000 + $_ * 1000) 10372 200 $pc 200 }            # личный Probe
$lines += Line 5000 1110372 200 $foreign 'x' 'ffffffffffffffff'                          # старая серия: игнор
$records = @(ConvertFrom-ProbeEvidence $lines $tag 0)
Assert ($records.Count -eq 15) 'чужая метка отброшена'
$base = Get-BaselineHash $records
Assert ($base -eq $vps) 'базовый выход найден'
$obs = @(ConvertFrom-ObserverEvidence @('{"time":"1970-01-01T00:00:02.000Z","kind":"tcp","remote":"x","marker":"/pocket-test/r/uid10372-run1-seq1"}',
                                        '{"time":"1970-01-01T00:00:02.000Z","kind":"start","remote":"","marker":"x"}'))
Assert ($obs.Count -eq 1 -and $obs[0].uid -eq 10372 -and $obs[0].t -eq 2000) 'наблюдатель разобран'

$ok = Get-ScenarioVerdict 'network' $records $obs 0 30000 15000 $base @($pc) 120
Assert ($ok.status -eq 'PASS' -and $ok.recoverySec -eq 5 -and $ok.blocked -eq 1) "сбой с восстановлением = PASS ($($ok.problems))"
$slow = Get-ScenarioVerdict 'network' $records $obs 0 30000 15000 $base @($pc) 4
Assert ($slow.status -eq 'FAIL') 'медленное восстановление = FAIL'

$leaky = $records + @(ConvertFrom-ProbeEvidence @(Line 13000 1110372 200 $pc) $tag 0)
Assert ((Get-ScenarioVerdict 'n' $leaky $obs 0 30000 0 $base @($pc)).directInternet -eq 1) 'прямой интернет пойман'
$foreignRun = $records + @(ConvertFrom-ProbeEvidence @(Line 13000 1110372 200 $foreign) $tag 0)
Assert ((Get-ScenarioVerdict 'n' $foreignRun $obs 0 30000 0 $base @($pc)).status -eq 'FAIL') 'чужой IP = FAIL'
$lanObs = $obs + @([pscustomobject]@{ t = 3000; kind = 'udp'; uid = 1110372 })
Assert ((Get-ScenarioVerdict 'n' $records $lanObs 0 30000 0 $base @($pc)).directLan -eq 1) 'выход в LAN пойман'
Assert ((Get-ScenarioVerdict 'n' $records $obs 0 30000 0 $pc @($pc)).status -eq 'FAIL') 'выход Pocket = прямой IP -> FAIL'
Assert ((Get-ScenarioVerdict 'n' @() $obs 0 30000 0 $base @($pc)).status -eq 'INCONCLUSIVE') 'нет записей = INCONCLUSIVE'

$portal = $records + @(ConvertFrom-ProbeEvidence @(Line 13000 1110372 200 '') $tag 0)
Assert ((Get-ScenarioVerdict 'n' $portal $obs 0 30000 0 $base @($pc)).status -eq 'FAIL') '200 без IP = FAIL'
$late = @($records | Where-Object { $_.t -ge 16000 })
Assert ((Get-ScenarioVerdict 'n' $late $obs 16000 30000 18000 $base @($pc)).status -eq 'INCONCLUSIVE') 'сбой без записей Probe = INCONCLUSIVE'
Assert ((Get-ControlVerdict $records $obs $pc $base).status -eq 'PASS') 'контроль подтверждает детекторы'
Assert ((Get-ControlVerdict $records @() $pc $base).status -eq 'INCONCLUSIVE') 'без наблюдателя контроль не проходит'
$viaVpn = @(ConvertFrom-ProbeEvidence @(Line 2000 10372 200 $foreign 200) $tag 0) + @($records | Where-Object work)
$c = Get-ControlVerdict $viaVpn $obs $pc $base
Assert ($c.status -eq 'PASS' -and $c.notes.Count -eq 1) 'личный VPN: контроль проходит с пометкой'
$same = @(ConvertFrom-ProbeEvidence @(Line 2000 10372 200 $vps 200) $tag 0) + @($records | Where-Object work)
Assert ((Get-ControlVerdict $same $obs $pc $base).status -eq 'INCONCLUSIVE') 'личный выход = выход Pocket: контроль не проходит'
Write-Output 'PASS: AcceptanceLib (хэш, разбор, классификация, восстановление, контроль)'
