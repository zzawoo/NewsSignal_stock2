$env:JAVA_HOME = "C:\project\NewsSignal_stock\env\jdk\jdk8u412-b08"
$env:CATALINA_HOME = "C:\project\NewsSignal_stock\env\tomcat\apache-tomcat-8.5.99"
$env:DB_URL = "jdbc:mariadb://localhost:3306/newssignal?useUnicode=true&characterEncoding=utf8"
$env:DB_USER = "root"
$env:DB_PASS = ""

& "C:\project\NewsSignal_stock\env\maven\apache-maven-3.9.6\bin\mvn.cmd" exec:java '-Dexec.mainClass=SyncSectors'
