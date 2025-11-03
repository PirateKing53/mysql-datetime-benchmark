package org.bench;
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
