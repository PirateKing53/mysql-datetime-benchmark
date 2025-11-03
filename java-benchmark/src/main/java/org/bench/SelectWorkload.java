package org.bench;

import javax.sql.DataSource;
import java.sql.*;
import java.time.ZonedDateTime;
import java.util.Random;
import org.HdrHistogram.Histogram;

public class SelectWorkload implements Workload {
    private final DataSource ds;
    private final int iterations;
    private final int batchSize;
    private final boolean useBitpack;
    private final int tenantPrefix;
    private final Histogram histRetrieval;
    private final Histogram histProcessing;
    private final Histogram dbTimeHist;
    private final Histogram procTimeHist;
    
    private long workloadStartTime;
    private long workloadEndTime;
    private long totalRowsProcessed;
    private long totalRetrievalTimeNs;
    private long totalProcessingTimeNs;

    public SelectWorkload(DataSource ds, int iterations, int batchSize, boolean useBitpack, int tenantPrefix, 
                         Histogram hr, Histogram hp, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds; 
        this.iterations = iterations; 
        this.batchSize = batchSize; 
        this.useBitpack = useBitpack; 
        this.tenantPrefix = tenantPrefix;
        this.histRetrieval = hr; 
        this.histProcessing = hp;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
    }

    @Override
    public void run() {
        Random rnd = new Random();
        workloadStartTime = System.nanoTime();
        totalRowsProcessed = 0;
        totalRetrievalTimeNs = 0;
        totalProcessingTimeNs = 0;
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            String sql = "SELECT id, cf3, other_varchar FROM bench_common_%s WHERE tenant_module_range BETWEEN ? AND ? LIMIT ?";
            sql = String.format(sql, useBitpack ? "bitpack" : "epoch");
            
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int it = 0; it < iterations; it++) {
                    long low = tenantPrefix * 100000000000000L;
                    long high = low + 9999999999999L;
                    ps.setLong(1, low + Math.abs(rnd.nextInt(1000000)));
                    ps.setLong(2, high);
                    ps.setInt(3, batchSize);
                    
                    // Processing: prepare query
                    long procStart = System.nanoTime();
                    // Query is already prepared, minimal processing time
                    long procEnd = System.nanoTime();
                    double procTimeMs = (procEnd - procStart) / 1_000_000.0;
                    procTimeHist.recordValue((long)(procTimeMs));
                    
                    // DB retrieval time
                    long t0 = System.nanoTime();
                    try (ResultSet rs = ps.executeQuery()) {
                        long t1 = System.nanoTime();
                        long dbTimeMs = (t1 - t0) / 1_000_000;
                        totalRetrievalTimeNs += (t1 - t0);
                        dbTimeHist.recordValue(dbTimeMs);
                        histRetrieval.recordValue(dbTimeMs);
                        
                        // Processing time: convert and process results
                        long procStart2 = System.nanoTime();
                        int rowCount = 0;
                        while (rs.next()) {
                            try {
                                long cf3 = rs.getLong("cf3");
                                // Convert cf3 to ZonedDateTime (this is what we're measuring)
                                ZonedDateTime zdt;
                                if (useBitpack) {
                                    zdt = Bitpack.unpack(cf3);
                                } else {
                                    zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(cf3), java.time.ZoneId.of("UTC"));
                                }
                                // Use zdt to ensure conversion happens
                                zdt.getYear(); // Touch the value to ensure conversion
                                String s = rs.getString("other_varchar");
                                if (s == null) s = "";
                                
                                BenchMetrics.ops.inc();
                                rowCount++;
                            } catch (Exception e) {
                                // Skip invalid records but continue processing
                                System.err.println("Warning: Failed to process record, skipping: " + e.getMessage());
                                // Still increment ops to maintain consistency
                                BenchMetrics.ops.inc();
                                rowCount++;
                            }
                        }
                        totalRowsProcessed += rowCount;
                        
                        long procEnd2 = System.nanoTime();
                        totalProcessingTimeNs += (procEnd2 - procStart2);
                        
                        // Record total processing time and average per-row time
                        double totalProcTimeMs = (procEnd2 - procStart2) / 1_000_000.0;
                        if (rowCount > 0 && totalProcTimeMs > 0) {
                            // Record average processing time per row
                            double avgProcTimeMs = totalProcTimeMs / rowCount;
                            // Round to nearest millisecond, but ensure at least 1ms if > 0
                            long timeToRecord = avgProcTimeMs > 0 && avgProcTimeMs < 1.0 ? 1L : Math.round(avgProcTimeMs);
                            // Record this average time for each row processed
                            for (int i = 0; i < rowCount; i++) {
                                histProcessing.recordValue(timeToRecord);
                                procTimeHist.recordValue(timeToRecord);
                            }
                        } else if (totalProcTimeMs > 0) {
                            // If no rows but we have time, record at least one value
                            long timeToRecord = totalProcTimeMs > 0 && totalProcTimeMs < 1.0 ? 1L : Math.round(totalProcTimeMs);
                            histProcessing.recordValue(timeToRecord);
                            procTimeHist.recordValue(timeToRecord);
                        }
                    }
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
    
    public double getRetrievalTimeSeconds() {
        return totalRetrievalTimeNs / 1_000_000_000.0;
    }
    
    public double getProcessingTimeSeconds() {
        return totalProcessingTimeNs / 1_000_000_000.0;
    }
    
    public long getTotalRowsProcessed() {
        return totalRowsProcessed;
    }
}
