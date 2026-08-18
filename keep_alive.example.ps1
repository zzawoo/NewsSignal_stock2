# ================================================================
#  NewsSignal Keep-Alive 워치독
#  - MariaDB(3306)와 Tomcat(8080)이 떠 있는지 확인, 내려가 있으면 다시 띄움
#  - 예약 작업(NewsSignalKeepAlive)으로 5분마다 실행되도록 등록됨
#  - 둘 다 살아있으면 아무 것도 하지 않음(멱등)
# ================================================================
$ErrorActionPreference = "SilentlyContinue"
$log = "C:\project\NewsSignal_stock2\keep_alive.log"
function Log($m) { "$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))  $m" | Out-File -Append -Encoding utf8 $log }

# 1) MariaDB 확인 → 없으면 기동
if (-not (Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue)) {
    Log "MariaDB DOWN -> starting mysqld"
    Start-Process -FilePath "C:\project\NewsSignal_stock2\env\mariadb\mariadb-10.1.48-winx64\bin\mysqld.exe" -ArgumentList "--console" -WindowStyle Hidden
    $dl = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $dl) {
        Start-Sleep -Seconds 2
        if (Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue) { break }
    }
}

# 1-2) Tailscale Funnel 확인 → 꺼져 있으면 재활성 (고정 URL https://newssignal.tail7ba397.ts.net 자동 유지)
$ts = "C:\Program Files\Tailscale\tailscale.exe"
if (Test-Path $ts) {
    try {
        $fs = (& $ts funnel status 2>&1 | Out-String)
        if ($fs -notmatch 'Funnel on') {
            Log "Funnel OFF -> re-enabling (8080)"
            & $ts funnel --bg 8080 2>&1 | Out-Null
        }
    } catch { Log "Funnel check error: $_" }
}

# 2) Tomcat 확인 → 없으면 분리(detached) 모드로 기동
#    포트가 아직 안 떠도 부팅 중인 java(Bootstrap) 프로세스가 있으면 2차 기동 금지(레이스/충돌 방지)
$booting = $false
try { $booting = [bool](Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match 'catalina|Bootstrap' }) } catch {}
if ((Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) -or $booting) {
    Log "OK (Tomcat up or booting)"
    return
}
Log "Tomcat DOWN -> starting (detached)"
$env:JAVA_HOME = "C:\project\NewsSignal_stock2\env\jdk\jdk8u412-b08"
$env:CATALINA_HOME = "C:\project\NewsSignal_stock2\env\tomcat\apache-tomcat-8.5.99"
$env:CATALINA_BASE = "C:\project\NewsSignal_stock2\env\tomcat\apache-tomcat-8.5.99"
$env:DB_URL = "jdbc:mariadb://localhost:3306/newssignal?useUnicode=true&characterEncoding=utf8"
$env:DB_USER = "root"
$env:DB_PASS = ""
$env:NAVER_CLIENT_ID = "<YOUR_NAVER_CLIENT_ID>"
$env:NAVER_CLIENT_SECRET = "<YOUR_NAVER_CLIENT_SECRET>"
$env:KIS_APP_KEY = "<YOUR_KIS_APP_KEY>"
$env:KIS_APP_SECRET = "<YOUR_KIS_APP_SECRET>"
$env:DART_API_KEY = "<YOUR_DART_API_KEY>"
$env:GEMINI_API_KEY = "<YOUR_GEMINI_API_KEY>"
$env:OPENAI_API_KEY = "dummy_key"
$env:GROQ_API_KEY = "<YOUR_GROQ_API_KEY>"
$env:KAKAO_REST_API_KEY = "<YOUR_KAKAO_REST_API_KEY>"
$env:KAKAO_CLIENT_SECRET = "<YOUR_KAKAO_CLIENT_SECRET>"
& "$env:CATALINA_HOME\bin\catalina.bat" start
Log "Tomcat start 호출 완료"
