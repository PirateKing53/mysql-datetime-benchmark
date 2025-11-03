package org.bench;
import javax.sql.DataSource;
import java.sql.*;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.Random;
import org.HdrHistogram.Histogram;

public class InsertWorkload implements Workload {
    private final DataSource ds;
    private final int rows;
    private final int batchSize;
    private final boolean useBitpack;
    private final int tenantPrefix;
    private final Histogram hist;
    private final Histogram dbTimeHist;
    private final Histogram procTimeHist;
    
    private static final long YEAR_2015_EPOCH = ZonedDateTime.of(2015, 1, 1, 0, 0, 0, 0, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli();
    private static final long YEAR_2025_EPOCH = ZonedDateTime.of(2025, 12, 31, 23, 59, 59, 999_000_000, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli();
    private static final long DATE_RANGE_MS = YEAR_2025_EPOCH - YEAR_2015_EPOCH;
    
    private long workloadStartTime;
    private long workloadEndTime;
    private int totalInserted;

    public InsertWorkload(DataSource ds, int rows, int batchSize, boolean useBitpack, int tenantPrefix, Histogram hist, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds; 
        this.rows = rows; 
        this.batchSize = batchSize; 
        this.useBitpack = useBitpack; 
        this.tenantPrefix = tenantPrefix; 
        this.hist = hist;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
    }

    @Override
    public void run() {
        Random rnd = new Random();
        workloadStartTime = System.nanoTime();
        totalInserted = 0;
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            
            String sql = "INSERT INTO bench_common_%s (cf3, tenant_module_range, other_bigint, other_decimal, other_varchar, other_blob, created_at, updated_at, flag_tiny) VALUES (?,?,?,?,?,?,?,?,?)";
            sql = String.format(sql, useBitpack ? "bitpack" : "epoch");
            
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int inserted = 0;
                while (inserted < rows) {
                    int thisBatch = Math.min(batchSize, rows - inserted);
                    
                    // Processing time: generate data
                    long procStart = System.nanoTime();
                    for (int i = 0; i < thisBatch; i++) {
                        // Generate random datetime between 2015-2025
                        long randomEpochMs = YEAR_2015_EPOCH + (long)(rnd.nextDouble() * DATE_RANGE_MS);
                        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(randomEpochMs), java.time.ZoneId.of("UTC"));
                        
                        long cf3 = useBitpack ? Bitpack.pack(zdt, tenantPrefix) : randomEpochMs;
                        
                        ps.setLong(1, cf3);
                        ps.setLong(2, tenantPrefix * 100000000000000L + Math.abs(rnd.nextInt(1000000)));
                        ps.setLong(3, Math.abs(rnd.nextLong()));
                        ps.setBigDecimal(4, new java.math.BigDecimal(Math.abs(rnd.nextDouble()*10000)).setScale(4, java.math.RoundingMode.HALF_UP));
                        ps.setString(5, "name-"+Math.abs(rnd.nextInt(1000000)));
                        ps.setBytes(6, ("blob-"+Math.abs(rnd.nextInt(1000000))).getBytes());
                        ps.setLong(7, System.currentTimeMillis());
                        ps.setLong(8, System.currentTimeMillis());
                        ps.setInt(9, Math.abs(rnd.nextInt(2)));
                        ps.addBatch();
                    }
                    long procEnd = System.nanoTime();
                    double procTimeMs = (procEnd - procStart) / 1_000_000.0;
                    procTimeHist.recordValue((long)(procTimeMs));
                    
                    // DB time: execute batch
                    long dbStart = System.nanoTime();
                    DeadlockRetry.executeWithRetry(c, () -> {
                        ps.executeBatch();
                        c.commit();
                    });
                    long dbEnd = System.nanoTime();
                    double dbTimeMs = (dbEnd - dbStart) / 1_000_000.0;
                    dbTimeHist.recordValue((long)(dbTimeMs));
                    
                    // Record latency: total batch time in milliseconds
                    // Record this batch time for each row in the batch (represents batch insert latency per row)
                    double totalTimeMs = (dbEnd - procStart) / 1_000_000.0;
                    // Round to nearest millisecond - use Math.ceil to ensure we don't lose very fast operations
                    // If time is > 0 but < 1ms, round up to 1ms to ensure it's recorded
                    long timeToRecord = totalTimeMs > 0 && totalTimeMs < 1.0 ? 1L : Math.round(totalTimeMs);
                    // Record each row with the batch time - this represents the effective latency per row in a batch
                    // This is more meaningful than dividing, which loses precision for large batches
                    for (int i = 0; i < thisBatch; i++) {
                        hist.recordValue(timeToRecord);
                    }
                    
                    inserted += thisBatch;
                    totalInserted = inserted;
                }
            }
            workloadEndTime = System.nanoTime();
        } catch (Exception ex) {
            workloadEndTime = System.nanoTime();
            ex.printStackTrace();
        }
    }
    
    public double getElapsedTimeSeconds() {
        if (workloadEndTime > workloadStartTime) {
            return (workloadEndTime - workloadStartTime) / 1_000_000_000.0;
        }
        return 0.0;
    }
    
    public long getTotalInserted() {
        return totalInserted;
    }
}
