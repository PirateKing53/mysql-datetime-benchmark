package org.bench;

/**
 * Central configuration class for benchmark parameters.
 * 
 * <p>This class provides access to all benchmark configuration values,
 * including database connection settings, default workload parameters,
 * and results directory. All values can be overridden via system properties.
 * 
 * <p><b>System Properties:</b>
 * <ul>
 *   <li>{@code db.url}: Database JDBC URL (default: MySQL on port 33306)</li>
 *   <li>{@code db.user}: Database username (default: admin)</li>
 *   <li>{@code db.pass}: Database password (default: admin)</li>
 *   <li>{@code bench.rows}: Total rows to process (default: 200000)</li>
 *   <li>{@code bench.batch}: Batch size (default: 1000)</li>
 *   <li>{@code bench.threads}: Number of threads (default: 8)</li>
 *   <li>{@code bench.results.dir}: Results directory (default: results)</li>
 * </ul>
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class Config {
    public static final String DB_URL = System.getProperty("db.url","jdbc:mysql://127.0.0.1:33306/benchdb?rewriteBatchedStatements=true&useServerPrepStmts=true");
    public static final String DB_USER = System.getProperty("db.user","admin");
    public static final String DB_PASS = System.getProperty("db.pass","admin");
    public static final int DEFAULT_ROWS = Integer.getInteger("bench.rows", 200000);
    public static final int DEFAULT_BATCH = Integer.getInteger("bench.batch", 1000);
    public static final int DEFAULT_THREADS = Integer.getInteger("bench.threads", 8);
    public static final String RESULTS_DIR = System.getProperty("bench.results.dir","results");
    
    // Database adapter instance
    public static final DatabaseAdapter DB_ADAPTER = DatabaseAdapter.fromUrl(DB_URL);
}
