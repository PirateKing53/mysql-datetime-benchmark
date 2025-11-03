package org.bench;
import javax.sql.DataSource;
import java.sql.*;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.Random;
import org.HdrHistogram.Histogram;

public class TxnMixedWorkload implements Workload {
    private final DataSource ds;
    private final int opsPerTxn;
    private final boolean useBitpack;
    private final Histogram hist;
    private final Histogram dbTimeHist;
    private final Histogram procTimeHist;
    
    private static final long YEAR_2015_EPOCH = ZonedDateTime.of(2015, 1, 1, 0, 0, 0, 0, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli();
    private static final long YEAR_2025_EPOCH = ZonedDateTime.of(2025, 12, 31, 23, 59, 59, 999_000_000, java.time.ZoneId.of("UTC")).toInstant().toEpochMilli();
    private static final long DATE_RANGE_MS = YEAR_2025_EPOCH - YEAR_2015_EPOCH;

    public TxnMixedWorkload(DataSource ds, int opsPerTxn, boolean useBitpack, Histogram hist, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds; 
        this.opsPerTxn = opsPerTxn; 
        this.useBitpack = useBitpack; 
        this.hist = hist;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
    }

    @Override
    public void run() {
        Random rnd = new Random();
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            
            for (int iter = 0; iter < 1000; iter++) {
                c.setAutoCommit(false);
                long totalStart = System.nanoTime();
                
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO bench_common_"+(useBitpack?"bitpack":"epoch")+" (cf3, tenant_module_range, other_bigint, other_decimal, other_varchar, other_blob, created_at, updated_at, flag_tiny) VALUES (?,?,?,?,?,?,?,?,?)");
                     PreparedStatement upd = c.prepareStatement("UPDATE bench_common_"+(useBitpack?"bitpack":"epoch")+" SET other_varchar=? WHERE id=?")) {
                    
                    // Processing: prepare data
                    long procStart = System.nanoTime();
                    for (int i = 0; i < opsPerTxn; i++) {
                        long randomEpochMs = YEAR_2015_EPOCH + (long)(rnd.nextDouble() * DATE_RANGE_MS);
                        ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(randomEpochMs), java.time.ZoneId.of("UTC"));
                        long cf3 = useBitpack ? Bitpack.pack(zdt, 1111111) : randomEpochMs;
                        
                        ins.setLong(1, cf3);
                        ins.setLong(2, 11111110000000000L + Math.abs(rnd.nextInt(1000000)));
                        ins.setLong(3, Math.abs(rnd.nextLong()));
                        ins.setBigDecimal(4, new java.math.BigDecimal(Math.abs(rnd.nextDouble()*10000)).setScale(4, java.math.RoundingMode.HALF_UP));
                        ins.setString(5, "txn-"+Math.abs(rnd.nextInt(1000000)));
                        ins.setBytes(6, ("b-"+Math.abs(rnd.nextInt(1000000))).getBytes());
                        ins.setLong(7, System.currentTimeMillis());
                        ins.setLong(8, System.currentTimeMillis());
                        ins.setInt(9, 0);
                        ins.addBatch();
                        
                        if (i % 2 == 0) {
                            upd.setString(1, "u-"+Math.abs(rnd.nextInt(1000000)));
                            upd.setLong(2, Math.abs(rnd.nextLong() % 1000) + 1);
                            upd.addBatch();
                        }
                    }
                    long procEnd = System.nanoTime();
                    double procTimeMs = (procEnd - procStart) / 1_000_000.0;
                    procTimeHist.recordValue((long)(procTimeMs));
                    
                    // DB execution
                    long dbStart = System.nanoTime();
                    DeadlockRetry.executeWithRetry(c, () -> {
                        ins.executeBatch();
                        upd.executeBatch();
                        c.commit();
                    });
                    long dbEnd = System.nanoTime();
                    double dbTimeMs = (dbEnd - dbStart) / 1_000_000.0;
                    dbTimeHist.recordValue((long)(dbTimeMs));
                }
                
                long totalEnd = System.nanoTime();
                double totalTimeMs = (totalEnd - totalStart) / 1_000_000.0;
                // Round to nearest millisecond, but ensure at least 1ms if > 0
                long timeToRecord = totalTimeMs > 0 && totalTimeMs < 1.0 ? 1L : Math.round(totalTimeMs);
                hist.recordValue(timeToRecord);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
