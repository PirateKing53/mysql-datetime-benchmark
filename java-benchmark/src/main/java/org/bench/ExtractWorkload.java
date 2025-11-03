package org.bench;
import javax.sql.DataSource;
import java.sql.*;
import org.HdrHistogram.Histogram;

public class ExtractWorkload implements Workload {
    private final DataSource ds;
    private final boolean useBitpack;
    private final Histogram hist;
    private final Histogram dbTimeHist;
    private final Histogram procTimeHist;

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
            String expr = useBitpack ? 
                "((cf3 >> 35) & 0x7FF) + 2000" : 
                "EXTRACT(YEAR FROM FROM_UNIXTIME(cf3/1000))";
            String sql = "SELECT "+expr+" as yr, COUNT(*) as cnt FROM bench_common_"+table+" GROUP BY yr HAVING cnt > 0 ORDER BY yr";
            
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
