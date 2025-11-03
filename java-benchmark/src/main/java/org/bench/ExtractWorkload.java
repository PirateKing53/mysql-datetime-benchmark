package org.bench;
import javax.sql.DataSource;
import java.sql.*;
import org.HdrHistogram.Histogram;

/**
 * Benchmark workload for GROUP BY operations with year extraction.
 * 
 * <p>This workload performs a GROUP BY query that extracts the year from the
 * datetime column and counts rows per year. It tests the performance difference
 * between epoch-based extraction (database date functions) and bitpack extraction
 * (bitwise operations).
 * 
 * <p>The workload measures three separate latency metrics:
 * <ul>
 *   <li><b>db_time</b>: Database execution time (query execution)</li>
 *   <li><b>processing_time</b>: Java-side processing time (query prep + result processing)</li>
 *   <li><b>total_time</b>: End-to-end time (prep + DB + result processing)</li>
 * </ul>
 * 
 * <p>Database-specific handling:
 * <ul>
 *   <li>MySQL: Allows aliases in GROUP BY clause</li>
 *   <li>PostgreSQL: Requires full expression in GROUP BY clause</li>
 * </ul>
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class ExtractWorkload implements Workload {
    private final DataSource ds;
    private final boolean useBitpack;
    private final Histogram hist;
    private final Histogram dbTimeHist;
    private final Histogram procTimeHist;

    /**
     * Creates a new extract workload.
     * 
     * @param ds The DataSource for database connections
     * @param useBitpack If true, use bitpack storage (bitwise extraction); if false, use epoch storage (date function extraction)
     * @param hist Histogram for total time metrics
     * @param dbTimeHist Histogram for database execution time metrics
     * @param procTimeHist Histogram for processing time metrics (query prep + result processing)
     */
    public ExtractWorkload(DataSource ds, boolean useBitpack, Histogram hist, Histogram dbTimeHist, Histogram procTimeHist) {
        this.ds = ds; 
        this.useBitpack = useBitpack; 
        this.hist = hist;
        this.dbTimeHist = dbTimeHist;
        this.procTimeHist = procTimeHist;
    }

    @Override
    public void run() {
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            String table = useBitpack ? "bitpack" : "epoch";
            DatabaseAdapter adapter = Config.DB_ADAPTER;
            
            // Use adapter to get database-specific year extraction
            String expr = adapter.getYearExtract("cf3", useBitpack);
            
            // PostgreSQL requires full expression in GROUP BY, not alias
            // MySQL allows alias in GROUP BY
            String groupByClause;
            if (adapter.getType() == DatabaseType.MYSQL) {
                // MySQL: can use alias
                groupByClause = "GROUP BY yr";
            } else {
                // PostgreSQL: must use full expression
                groupByClause = "GROUP BY " + expr;
            }
            
            String sql = "SELECT "+expr+" as yr, COUNT(*) as cnt FROM bench_common_"+table+" "+
                        groupByClause+" HAVING COUNT(*) > 0 ORDER BY yr";
            
            // Processing: prepare query
            long procStart = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                long procEnd = System.nanoTime();
                double procTimeMs = (procEnd - procStart) / 1_000_000.0;
                procTimeHist.recordValue((long)(procTimeMs));
                
                // DB execution time
                long t0 = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    long t1 = System.nanoTime();
                    long dbTimeMs = (t1 - t0) / 1_000_000;
                    dbTimeHist.recordValue(dbTimeMs);
                    
                    // Processing: process results
                    long procStart2 = System.nanoTime();
                    while (rs.next()) {
                        rs.getInt("yr");
                        rs.getLong("cnt");
                    }
                    long procEnd2 = System.nanoTime();
                    long procTimeMs2 = (procEnd2 - procStart2) / 1_000_000;
                    procTimeHist.recordValue(procTimeMs2);
                    
                    // Record total_time (procStart -> procEnd2): includes prep + DB + result processing
                    long totalTimeMs = (procEnd2 - procStart) / 1_000_000;
                    hist.recordValue(totalTimeMs);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
