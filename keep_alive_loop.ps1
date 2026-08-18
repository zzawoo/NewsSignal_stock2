# ================================================================
#  NewsSignal 상시 워치도그 루프
#  - 로그인 시 시작 프로그램에서 자동 실행(관리자 불필요, 배터리에서도 동작)
#  - 3분마다 keep_alive.ps1(MariaDB/Tomcat/Tailscale Funnel 확인·복구) 호출
#  - 예약작업(NewsSignalKeepAlive)과 병행해도 무해(둘 다 멱등)
# ================================================================
$ErrorActionPreference = "SilentlyContinue"
$loopLog = "C:\project\NewsSignal_stock2\keep_alive_loop.log"
"$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))  loop watchdog started (PID $PID)" | Out-File -Append -Encoding utf8 $loopLog
while ($true) {
    try { & "C:\project\NewsSignal_stock2\keep_alive.ps1" } catch {
        "$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))  loop error: $_" | Out-File -Append -Encoding utf8 $loopLog
    }
    Start-Sleep -Seconds 180
}
