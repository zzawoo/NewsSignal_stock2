# ================================================================
#  NewsSignal 서버 시작 스크립트
#  실행 방법: 우클릭 > PowerShell로 실행  또는
#             PS> .\start.ps1
# ================================================================

# ── 환경 설정 ──────────────────────────────────────────────────
$env:JAVA_HOME = "C:\project\NewsSignal_stock2\env\jdk\jdk8u412-b08"
$env:CATALINA_HOME = "C:\project\NewsSignal_stock2\env\tomcat\apache-tomcat-8.5.99"

# 데이터베이스
$env:DB_URL = "jdbc:mariadb://localhost:3306/newssignal?useUnicode=true&characterEncoding=utf8"
$env:DB_USER = "root"
$env:DB_PASS = ""

# 네이버 뉴스 Search API
$env:NAVER_CLIENT_ID = "<YOUR_NAVER_CLIENT_ID>"
$env:NAVER_CLIENT_SECRET = "<YOUR_NAVER_CLIENT_SECRET>"

# KIS (한국투자증권) OpenAPI
$env:KIS_APP_KEY = "<YOUR_KIS_APP_KEY>"
$env:KIS_APP_SECRET = "<YOUR_KIS_APP_SECRET>"

# DART OpenAPI (금융감독원)
$env:DART_API_KEY = "<YOUR_DART_API_KEY>"

# AI API 설정 (분석 제공자는 collect_settings.analyze.provider 로 선택: groq | ollama | gemini)
$env:GEMINI_API_KEY = "<YOUR_GEMINI_API_KEY>"
$env:OPENAI_API_KEY = "dummy_key"
# Groq (무료·빠름, 클라우드). console.groq.com/keys 에서 무료 발급한 키를 넣으세요 (gsk_...로 시작).
$env:GROQ_API_KEY = "<YOUR_GROQ_API_KEY>"

# ── MariaDB 확인 및 자동 기동 ─────────────────────────────────
# (DB가 안 떠 있으면 앱 컨텍스트가 HikariCP 풀 초기화 실패로 시작되지 않아 404가 난다)
$mysqldExe = "C:\project\NewsSignal_stock2\env\mariadb\mariadb-10.1.48-winx64\bin\mysqld.exe"
if (Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "✓ MariaDB 이미 실행 중 (3306)" -ForegroundColor Green
} else {
    Write-Host "▶ MariaDB 시작 중..." -ForegroundColor Cyan
    Start-Process -FilePath $mysqldExe -ArgumentList "--console" -WindowStyle Hidden
    $dbDeadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $dbDeadline) {
        Start-Sleep -Seconds 2
        if (Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue) { break }
    }
    if (Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "✓ MariaDB 시작 완료" -ForegroundColor Green
    } else {
        Write-Host "✗ MariaDB 시작 실패 (3306 응답 없음). 종료합니다." -ForegroundColor Red
        exit 1
    }
}

# ── Ollama(로컬 LLM) 확인 및 자동 기동 ────────────────────────
# (분석 제공자 analyze.provider=ollama. 서버가 꺼져 있으면 분석 단계가 동작하지 않음)
$ollamaExe = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"
if (Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "✓ Ollama 이미 실행 중 (11434)" -ForegroundColor Green
} elseif (Test-Path $ollamaExe) {
    Write-Host "▶ Ollama 시작 중..." -ForegroundColor Cyan
    Start-Process -FilePath $ollamaExe -ArgumentList "serve" -WindowStyle Hidden
    $olDeadline = (Get-Date).AddSeconds(25)
    while ((Get-Date) -lt $olDeadline) {
        Start-Sleep -Seconds 2
        if (Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue) { break }
    }
    if (Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "✓ Ollama 시작 완료" -ForegroundColor Green
    } else {
        Write-Host "⚠ Ollama 시작 확인 실패 — 분석 단계가 동작 안 할 수 있음(수집/대시보드는 정상)" -ForegroundColor Yellow
    }
} else {
    Write-Host "⚠ Ollama 미설치 — analyze.provider=ollama면 분석이 동작 안 함" -ForegroundColor Yellow
}

# ── 빌드 ──────────────────────────────────────────────────────
Write-Host "▶ Maven 빌드 중..." -ForegroundColor Cyan
& "C:\project\NewsSignal_stock2\env\maven\apache-maven-3.9.6\bin\mvn.cmd" clean package -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "✗ 빌드 실패. 종료합니다." -ForegroundColor Red
    exit 1
}
Write-Host "✓ 빌드 완료" -ForegroundColor Green

# ── WAR 배포 ──────────────────────────────────────────────────
# sibling 프로젝트(NewsSignal_stock)와 같은 Tomcat을 쓰므로 컨텍스트를 newssignal2로 분리 (URL 충돌 방지)
$warSrc = "target\newssignal.war"
$warDest = "$env:CATALINA_HOME\webapps\newssignal2.war"
Copy-Item -Path $warSrc -Destination $warDest -Force
Write-Host "✓ WAR 배포 완료: $warDest" -ForegroundColor Green

# ── Tomcat 시작 ───────────────────────────────────────────────
Write-Host ""
Write-Host "▶ Tomcat 시작 중...  http://localhost:8081/newssignal2/user/dashboard.jsp" -ForegroundColor Cyan
Write-Host "  (종료하려면 Ctrl+C 를 누르세요)" -ForegroundColor Gray
Write-Host ""

& "$env:CATALINA_HOME\bin\catalina.bat" run
