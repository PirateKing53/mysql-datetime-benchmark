package org.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

public class DBPool {
    private static HikariDataSource ds;
    
    static {
        // Ensure JDBC drivers are loaded
        try {
            // MySQL driver
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            // MySQL driver not found, continue
        }
        try {
            // PostgreSQL driver
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            // PostgreSQL driver not found, continue
        }
    }

    public static synchronized DataSource getDataSource(int poolSize) {
        if (ds == null) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(Config.DB_URL);
            cfg.setUsername(Config.DB_USER);
            cfg.setPassword(Config.DB_PASS);
            cfg.setMaximumPoolSize(poolSize);
            cfg.setMinimumIdle(Math.max(2, poolSize/2));
            
            // Use database adapter to configure connection properties
            Config.DB_ADAPTER.configureConnectionProperties(cfg);
            
            ds = new HikariDataSource(cfg);
        }
        return ds;
    }

    public static synchronized void close() {
        if (ds != null) ds.close();
    }
}
