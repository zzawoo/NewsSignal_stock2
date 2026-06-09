package com.newssignal.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * DB 커넥션 풀. HikariCP 4.0.x (JDK8 호환).
 * 실제 운영에서는 자격증명을 환경변수/외부 설정으로 분리한다(계획서 보안 9장).
 */
public final class Db {

    private static final HikariDataSource DS;

    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(env("DB_URL",
                "jdbc:mariadb://localhost:3306/newssignal?useUnicode=true&characterEncoding=utf8"));
        cfg.setUsername(env("DB_USER", "newssignal"));
        cfg.setPassword(env("DB_PASS", "change_me"));
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(5000);
        cfg.setPoolName("newssignal-pool");
        DS = new HikariDataSource(cfg);
    }

    private Db() {}

    public static Connection conn() throws SQLException {
        return DS.getConnection();
    }

    public static void shutdown() {
        if (DS != null && !DS.isClosed()) DS.close();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
