package org.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class DBPool {
    private static HikariDataSource ds;

    public static synchronized DataSource getDataSource(int poolSize) {
        if (ds == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(Config.DB_URL);
            cfg.setUsername(Config.DB_USER);
            cfg.setPassword(Config.DB_PASS);
            cfg.setMaximumPoolSize(poolSize);
            cfg.setMinimumIdle(Math.max(2, poolSize/2));
            cfg.addDataSourceProperty("cachePrepStmts", "true");
            cfg.addDataSourceProperty("prepStmtCacheSize", "250");
            cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            ds = new HikariDataSource(cfg);
        }
        return ds;
    }

    public static synchronized void close() {
        if (ds != null) ds.close();
    }
}
