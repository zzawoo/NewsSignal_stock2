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
