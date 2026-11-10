param(
    [Parameter(Mandatory=$true)][string]$ListenAddress,
    [ValidateRange(1024,65535)][int]$Port=55887,
    [ValidateRange(1,3600)][int]$Seconds=90,
    [Parameter(Mandatory=$true)][string]$EvidencePath
)
$ErrorActionPreference='Stop'
$taskAddress=[Net.IPAddress]::Parse($ListenAddress)
$taskTcp=[Net.Sockets.TcpListener]::new($taskAddress,$Port)
$taskUdp=[Net.Sockets.UdpClient]::new([Net.IPEndPoint]::new($taskAddress,$Port))
$taskWriter=[IO.StreamWriter]::new($EvidencePath,$false,[Text.UTF8Encoding]::new($false))
$taskCount=0
function Write-Observation($kind,$remote,$marker) {
    $taskWriter.WriteLine(([ordered]@{time=[DateTime]::UtcNow.ToString('o');kind=$kind;remote=$remote;marker=$marker} | ConvertTo-Json -Compress))
    $taskWriter.Flush()
}
try {
    $taskTcp.Start()
    Write-Observation 'start' '' ($ListenAddress+':'+$Port)
    $taskTimer=[Diagnostics.Stopwatch]::StartNew()
    while($taskTimer.Elapsed.TotalSeconds -lt $Seconds) {
        if($taskTcp.Pending()) {
            $taskClient=$taskTcp.AcceptTcpClient()
            try {
                $taskClient.ReceiveTimeout=500
                $taskStream=$taskClient.GetStream()
                $taskBuffer=[byte[]]::new(4096)
                $taskRead=$taskStream.Read($taskBuffer,0,$taskBuffer.Length)
                $taskLine=[Text.Encoding]::ASCII.GetString($taskBuffer,0,$taskRead).Split("`n")[0].Trim()
                if($taskLine -match '^GET (/pocket-test/[a-zA-Z0-9/_?=.-]{1,200}) HTTP/1\.[01]$') {
                    Write-Observation 'tcp' ($taskClient.Client.RemoteEndPoint.ToString()) $Matches[1]
                    $taskCount++
                }
                $taskResponse=[Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Length: 16`r`nConnection: close`r`nContent-Type: text/plain`r`n`r`npocket-target-ok")
                $taskStream.Write($taskResponse,0,$taskResponse.Length)
            } catch [IO.IOException] {} finally {$taskClient.Dispose()}
        }
        if($taskUdp.Available -gt 0) {
            $taskPeer=[Net.IPEndPoint]::new([Net.IPAddress]::Any,0)
            $taskPacket=$taskUdp.Receive([ref]$taskPeer)
            $taskMarker=[Text.Encoding]::UTF8.GetString($taskPacket)
            if($taskMarker -match '^PocketProbe uid=[0-9]+ time=[0-9]+ token=[0-9]+$') {
                Write-Observation 'udp' ($taskPeer.ToString()) $taskMarker
                [void]$taskUdp.Send($taskPacket,$taskPacket.Length,$taskPeer)
                $taskCount++
            }
        }
        Start-Sleep -Milliseconds 40
    }
    Write-Observation 'complete' '' ([string]$taskCount)
    Write-Output "Observation complete: $taskCount marked requests; $EvidencePath"
} finally {
    $taskTcp.Stop();$taskUdp.Dispose();$taskWriter.Dispose()
}
