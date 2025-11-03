package org.bench;

import javax.sql.DataSource;
import java.sql.*;
import org.HdrHistogram.Histogram;

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

    public UpdateWorkload(DataSource ds, int batchSize, boolean useRange, boolean useBitpack, Histogram hist, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds;
        this.batchSize = batchSize;
        this.useBitpack = useBitpack;
        this.hist = hist;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
        this.tenantPrefix = 1111111; // Default tenant
    }
    
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
            String sql = "UPDATE bench_common_"+table+" SET cf3 = cf3 + 1000 WHERE tenant_module_range BETWEEN ? AND ? LIMIT ?";
            
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // Use the same range as inserts to match the data
                long low = tenantPrefix * 100000000000000L;
                long high = low + 9999999999999L;
                
                // Run updates in a loop until we've updated enough rows or hit limit
                int maxIterations = 1000; // Safety limit
                while (iterations < maxIterations && totalUpdated < 10000) {
                    // Processing: prepare statement
                    long procStart = System.nanoTime();
                    ps.setLong(1, low + (iterations * 1000000)); // Shift range slightly each iteration
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
                    // Record once per update operation (not per row, since UPDATE returns count)
                    hist.recordValue((long)totalTimeMs);
                    
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
    
    public double getElapsedTimeSeconds() {
        if (workloadEndTime > workloadStartTime) {
            return (workloadEndTime - workloadStartTime) / 1_000_000_000.0;
        }
        return 0.0;
    }
    
    public long getTotalUpdated() {
        return totalUpdated;
    }
    
    public int getIterations() {
        return iterations;
    }
}
