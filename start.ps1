# ================================================================
#  NewsSignal 서버 시작 스크립트
#  실행 방법: 우클릭 > PowerShell로 실행  또는
#             PS> .\start.ps1
# ================================================================

# ── 환경 설정 ──────────────────────────────────────────────────
$env:JAVA_HOME = "C:\project\env\jdk\jdk8u412-b08"
$env:CATALINA_HOME = "C:\project\env\tomcat\apache-tomcat-8.5.99"

# 데이터베이스
$env:DB_URL = "jdbc:mariadb://localhost:3306/newssignal?useUnicode=true&characterEncoding=utf8"
$env:DB_USER = "root"
$env:DB_PASS = ""

# 네이버 뉴스 Search API
$env:NAVER_CLIENT_ID = "SCNsodOMzHSqMOGdiHNj"
$env:NAVER_CLIENT_SECRET = "ZTpWXbnfNq"

# KIS (한국투자증권) OpenAPI
$env:KIS_APP_KEY = "PScA1yvZGmgXiz7nvKeEvIvye0Rr7NIv2jon"
$env:KIS_APP_SECRET = "MfWXEjFnJ9ISUtk3B3M/opXxhfgq11Vlq7brhwAqIPYxpGmDo4Lshqrfabfgowh7i3eVfjwjRpJHvMBEFaFMRiFpHKLM8nEI4GXISgRDHxOF30ep0J0+MWeTqlb9cgpmG3VykCHUnoYcQ1PXCiHK8xmE16TzPws5ZeBQKkOau24rRbNZvB8="

# DART OpenAPI (금융감독원)
$env:DART_API_KEY = "81952a816a0667fdd3e51b69062b52ce32903818"

# AI API 설정 (429 에러 방지용 로컬 목업 강제 설정)
$env:OPENAI_API_KEY = "dummy_key"
$env:GEMINI_API_KEY = "dummy_key"

# ── 빌드 ──────────────────────────────────────────────────────
Write-Host "▶ Maven 빌드 중..." -ForegroundColor Cyan
& "C:\project\env\maven\apache-maven-3.9.6\bin\mvn.cmd" clean package -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "✗ 빌드 실패. 종료합니다." -ForegroundColor Red
    exit 1
}
Write-Host "✓ 빌드 완료" -ForegroundColor Green

# ── WAR 배포 ──────────────────────────────────────────────────
$warSrc = "target\newssignal.war"
$warDest = "$env:CATALINA_HOME\webapps\newssignal.war"
Copy-Item -Path $warSrc -Destination $warDest -Force
Write-Host "✓ WAR 배포 완료: $warDest" -ForegroundColor Green

# ── Tomcat 시작 ───────────────────────────────────────────────
Write-Host ""
Write-Host "▶ Tomcat 시작 중...  http://localhost:8080/newssignal/user/dashboard.jsp" -ForegroundColor Cyan
Write-Host "  (종료하려면 Ctrl+C 를 누르세요)" -ForegroundColor Gray
Write-Host ""

& "$env:CATALINA_HOME\bin\catalina.bat" run
