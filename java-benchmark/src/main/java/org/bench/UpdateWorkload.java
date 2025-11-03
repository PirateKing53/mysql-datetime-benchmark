package org.bench;

import javax.sql.DataSource;
import java.sql.*;
import org.HdrHistogram.Histogram;

/**
 * Benchmark workload for range-based update operations.
 * 
 * <p>This workload performs batch updates on the datetime column ({@code cf3})
 * within specific tenant ranges. Updates are executed in batches with LIMIT
 * clauses (handled differently for MySQL vs PostgreSQL).
 * 
 * <p>The workload measures three separate latency metrics:
 * <ul>
 *   <li><b>db_time</b>: Database execution time (UPDATE statement execution)</li>
 *   <li><b>processing_time</b>: Java-side processing time (statement preparation, parameter binding)</li>
 *   <li><b>total_time</b>: End-to-end time per update operation</li>
 * </ul>
 * 
 * <p>Uses {@code READ_COMMITTED} isolation level and includes deadlock retry logic.
 * The workload continues updating until a target number of rows is updated or
 * maximum iterations are reached.
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class UpdateWorkload implements Workload {

    private final DataSource ds;
    private final int batchSize;
    private final boolean useBitpack;
    private final Histogram hist;
    private final Histogram dbTimeHist;
    private final Histogram procTimeHist;
    private final int tenantPrefix;
    
    private long workloadStartTime;
    private long workloadEndTime;
    private long totalUpdated;
    private int iterations;

    /**
     * Creates a new update workload (legacy constructor with default tenant).
     * 
     * @param ds The DataSource for database connections
     * @param batchSize Number of rows to update per batch
     * @param useRange Whether to use range-based updates (currently unused, kept for compatibility)
     * @param useBitpack If true, use bitpack storage; if false, use epoch storage
     * @param hist Histogram for total time metrics
     * @param dbTimeHist Histogram for database execution time metrics
     * @param procTimeHist Histogram for processing time metrics
     */
    public UpdateWorkload(DataSource ds, int batchSize, boolean useRange, boolean useBitpack, Histogram hist, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds;
        this.batchSize = batchSize;
        this.useBitpack = useBitpack;
        this.hist = hist;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
        this.tenantPrefix = 1111111; // Default tenant
    }
    
    /**
     * Creates a new update workload with explicit tenant prefix.
     * 
     * @param ds The DataSource for database connections
     * @param batchSize Number of rows to update per batch
     * @param useRange Whether to use range-based updates (currently unused, kept for compatibility)
     * @param useBitpack If true, use bitpack storage; if false, use epoch storage
     * @param tenantPrefix Tenant identifier prefix for tenant_module_range filtering
     * @param hist Histogram for total time metrics
     * @param dbTimeHist Histogram for database execution time metrics
     * @param procTimeHist Histogram for processing time metrics
     */
    public UpdateWorkload(DataSource ds, int batchSize, boolean useRange, boolean useBitpack, int tenantPrefix, Histogram hist, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds;
        this.batchSize = batchSize;
        this.useBitpack = useBitpack;
        this.hist = hist;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
        this.tenantPrefix = tenantPrefix;
    }

    @Override
    public void run() {
        workloadStartTime = System.nanoTime();
        totalUpdated = 0;
        iterations = 0;
        try (Connection conn = ds.getConnection()) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            
            String table = useBitpack ? "bitpack" : "epoch";
            DatabaseAdapter adapter = Config.DB_ADAPTER;
            
            // Build base UPDATE SQL - adapter will handle LIMIT syntax
            String baseSql = "UPDATE bench_common_"+table+" SET cf3 = cf3 + 1000 WHERE tenant_module_range BETWEEN ? AND ?";
            String sql;
            if (adapter.supportsLimitInUpdateDelete()) {
                sql = baseSql + " LIMIT ?";
            } else {
                // PostgreSQL: Use subquery with LIMIT
                sql = "UPDATE bench_common_"+table+" SET cf3 = cf3 + 1000 " +
                      "WHERE id IN (SELECT id FROM bench_common_"+table+" WHERE tenant_module_range BETWEEN ? AND ? LIMIT ?)";
            }
            
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // Use the same range as inserts to match the data
                long low = tenantPrefix * 100000000000000L;
                long high = low + 9999999999999L;
                
                // Run updates in a loop until we've updated enough rows or hit limit
                int maxIterations = 1000; // Safety limit
                while (iterations < maxIterations && totalUpdated < 10000) {
                    // Processing: prepare statement
                    long procStart = System.nanoTime();
                    long lowVal = low + (iterations * 1000000); // Shift range slightly each iteration
                    // Parameters are the same for both MySQL and PostgreSQL (low, high, limit)
                    ps.setLong(1, lowVal);
                    ps.setLong(2, high);
                    ps.setInt(3, batchSize);
                    long procEnd = System.nanoTime();
                    double procTimeMs = (procEnd - procStart) / 1_000_000.0;
                    procTimeHist.recordValue((long)(procTimeMs));
                    
                    // DB execution
                    long dbStart = System.nanoTime();
                    int[] updated = new int[1];
                    DeadlockRetry.executeWithRetry(conn, () -> {
                        updated[0] = ps.executeUpdate();
                        conn.commit();
                    });
                    long dbEnd = System.nanoTime();
                    double dbTimeMs = (dbEnd - dbStart) / 1_000_000.0;
                    dbTimeHist.recordValue((long)(dbTimeMs));
                    
                    // Always record to histogram
                    // Record total batch time (not divided) - represents the update operation latency
                    double totalTimeMs = (dbEnd - procStart) / 1_000_000.0;
                    // Round to nearest millisecond, but ensure at least 1ms if > 0
                    long timeToRecord = totalTimeMs > 0 && totalTimeMs < 1.0 ? 1L : Math.round(totalTimeMs);
                    // Record once per update operation (not per row, since UPDATE returns count)
                    hist.recordValue(timeToRecord);
                    
                    if (updated[0] > 0) {
                        totalUpdated += updated[0];
                    } else {
                        // If no rows updated, break to avoid infinite loop
                        break;
                    }
                    
                    iterations++;
                }
            }
            workloadEndTime = System.nanoTime();
        } catch (Exception e) {
            workloadEndTime = System.nanoTime();
            e.printStackTrace();
        }
    }
    
    /**
     * Gets the total elapsed time for the workload execution.
     * 
     * @return Elapsed time in seconds, or 0.0 if workload hasn't completed
     */
    public double getElapsedTimeSeconds() {
        if (workloadEndTime > workloadStartTime) {
            return (workloadEndTime - workloadStartTime) / 1_000_000_000.0;
        }
        return 0.0;
    }
    
    /**
     * Gets the total number of rows updated.
     * 
     * @return Total rows updated
     */
    public long getTotalUpdated() {
        return totalUpdated;
    }
    
    /**
     * Gets the number of update iterations performed.
     * 
     * @return Number of iterations
     */
    public int getIterations() {
        return iterations;
    }
}
