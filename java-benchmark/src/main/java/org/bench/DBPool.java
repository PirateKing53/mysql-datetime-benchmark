package org.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Database connection pool manager using HikariCP.
 * 
 * <p>This class provides a singleton {@code DataSource} for database connections,
 * automatically loading the appropriate JDBC driver based on the connection URL
 * (MySQL or PostgreSQL). The pool size is configurable and connection properties
 * are set according to the database type.
 * 
 * <p>JDBC drivers are explicitly loaded in a static initializer to ensure they're
 * available when needed, supporting both MySQL and PostgreSQL drivers.
 * 
 * <p>The connection pool configuration is optimized for the benchmark workloads:
 * <ul>
 *   <li>Maximum pool size: thread count + 4</li>
 *   <li>Minimum idle: half of maximum pool size (minimum 2)</li>
 *   <li>Database-specific optimizations (batch rewriting, prepared statement caching)</li>
 * </ul>
 * 
 * @author krishna.sundar
 * @version 1.0
 */
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

    /**
     * Gets or creates the singleton database connection pool.
     * 
     * <p>If the pool doesn't exist, it creates a new HikariCP configuration
     * with the specified pool size and database-specific optimizations.
     * 
     * @param poolSize The maximum number of connections in the pool
     * @return A configured {@code DataSource} instance (singleton)
     */
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

    /**
     * Closes the database connection pool and releases all resources.
     * 
     * <p>Should be called at the end of the benchmark to properly cleanup connections.
     */
    public static synchronized void close() {
        if (ds != null) ds.close();
    }
}
