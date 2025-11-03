package org.bench;
import javax.sql.DataSource;
import java.sql.*;
import org.HdrHistogram.Histogram;

public class DeleteWorkload implements Workload {
    private final DataSource ds;
    private final int batchSize;
    private final boolean useBitpack;
    private final Histogram hist;
    private final Histogram dbTimeHist;
    private final Histogram procTimeHist;

    public DeleteWorkload(DataSource ds, int batchSize, boolean useBitpack, Histogram hist, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds; 
        this.batchSize = batchSize; 
        this.useBitpack = useBitpack; 
        this.hist = hist;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
    }

    @Override
    public void run() {
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            String table = useBitpack ? "bitpack" : "epoch";
            DatabaseAdapter adapter = Config.DB_ADAPTER;
            
            // Build DELETE SQL - adapter handles LIMIT syntax
            String sql;
            if (adapter.supportsLimitInUpdateDelete()) {
                sql = "DELETE FROM bench_common_"+table+" WHERE tenant_module_range BETWEEN ? AND ? LIMIT ?";
            } else {
                // PostgreSQL: Use subquery with LIMIT
                sql = "DELETE FROM bench_common_"+table+" " +
                      "WHERE id IN (SELECT id FROM bench_common_"+table+" WHERE tenant_module_range BETWEEN ? AND ? LIMIT ?)";
            }
            
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                long low = 11111110000000000L;
                long high = low + 9999999999999L;
                int deleted = 0;
                
                while (deleted < 100000) {
                    // Processing: prepare statement
                    long procStart = System.nanoTime();
                    ps.setLong(1, low);
                    ps.setLong(2, high);
                    ps.setInt(3, batchSize);
                    long procEnd = System.nanoTime();
                    double procTimeMs = (procEnd - procStart) / 1_000_000.0;
                    procTimeHist.recordValue((long)(procTimeMs));
                    
                    // DB execution
                    long dbStart = System.nanoTime();
                    int[] cnt = new int[1];
                    DeadlockRetry.executeWithRetry(c, () -> {
                        cnt[0] = ps.executeUpdate();
                        c.commit();
                    });
                    long dbEnd = System.nanoTime();
                    double dbTimeMs = (dbEnd - dbStart) / 1_000_000.0;
                    dbTimeHist.recordValue((long)(dbTimeMs));
                    
                    long totalEnd = System.nanoTime();
                    double totalTimeMs = (totalEnd - procStart) / 1_000_000.0;
                    // Round to nearest millisecond, but ensure at least 1ms if > 0
                    long timeToRecord = totalTimeMs > 0 && totalTimeMs < 1.0 ? 1L : Math.round(totalTimeMs);
                    hist.recordValue(timeToRecord);
                    
                    if (cnt[0] == 0) break;
                    deleted += cnt[0];
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
