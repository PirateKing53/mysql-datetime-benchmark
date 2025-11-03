package org.bench;

import javax.sql.DataSource;
import java.util.concurrent.*;
import org.HdrHistogram.Histogram;

/**
 * Main entry point for the datetime storage benchmark suite.
 * 
 * <p>This benchmark compares two datetime storage models (Epoch vs Bitpack) across
 * multiple database systems (MySQL 5.7 and PostgreSQL 9.6 + Citus). It measures
 * latency, throughput, database execution time, and processing overhead for various
 * database operations including inserts, updates, selects, extracts, transactions,
 * and deletes.
 * 
 * <p>The benchmark supports the following command-line arguments:
 * <ul>
 *   <li>{@code --model epoch|bitpack}: Select the storage model (default: epoch)</li>
 * </ul>
 * 
 * <p>System properties:
 * <ul>
 *   <li>{@code bench.threads}: Number of concurrent threads (default: 8)</li>
 *   <li>{@code bench.rows}: Total number of rows to process (default: 200000)</li>
 *   <li>{@code bench.batch}: Batch size for operations (default: 1000)</li>
 *   <li>{@code bench.tenant}: Tenant prefix identifier (default: 1111111)</li>
 *   <li>{@code db.url}: Database connection URL</li>
 *   <li>{@code db.user}: Database username</li>
 *   <li>{@code db.pass}: Database password</li>
 *   <li>{@code bench.results.dir}: Results directory (default: results)</li>
 * </ul>
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class Main {
    public static void main(String[] args) throws Exception {
        // Parse command line arguments
        boolean bitpack = false;
        for (int i = 0; i < args.length; i++) {
            if ("--model".equals(args[i]) && i + 1 < args.length) {
                String model = args[i + 1].toLowerCase();
                bitpack = "bitpack".equals(model);
                break;
            }
        }
        // Fallback to system property if no --model arg
        if (args.length == 0 || !args[0].equals("--model")) {
            bitpack = Boolean.getBoolean("bench.bitpack");
        }
        
        int threads = Integer.getInteger("bench.threads", Config.DEFAULT_THREADS);
        int rows = Integer.getInteger("bench.rows", Config.DEFAULT_ROWS);
        int batch = Integer.getInteger("bench.batch", Config.DEFAULT_BATCH);
        int tenant = Integer.getInteger("bench.tenant", 1111111);
        
        String model = bitpack ? "bitpack" : "epoch";
        System.out.println("Starting benchmark with model: " + model);

        int metricsPort = bitpack ? 9101 : 9100;
        BenchMetrics.startHttpServer(metricsPort);

        DataSource ds = DBPool.getDataSource(threads + 4);

        // Reset tables
        DBSetup.createTables(ds);
        DBSetup.clearTables(ds);

        System.out.println("Starting full benchmark. threads="+threads+" rows="+rows+" batch="+batch+" model="+model);
        
        // Reset summary
        ReportWriter.resetSummary();

        // Prepare histograms for each workload (total, db_time, processing_time)
        Histogram insertHist = new Histogram(3600000000000L, 3);
        Histogram insertDbTime = new Histogram(3600000000000L, 3);
        Histogram insertProcTime = new Histogram(3600000000000L, 3);
        
        Histogram updateHist = new Histogram(3600000000000L, 3);
        Histogram updateDbTime = new Histogram(3600000000000L, 3);
        Histogram updateProcTime = new Histogram(3600000000000L, 3);
        
        Histogram deleteHist = new Histogram(3600000000000L, 3);
        Histogram deleteDbTime = new Histogram(3600000000000L, 3);
        Histogram deleteProcTime = new Histogram(3600000000000L, 3);
        
        Histogram extractHist = new Histogram(3600000000000L, 3);
        Histogram extractDbTime = new Histogram(3600000000000L, 3);
        Histogram extractProcTime = new Histogram(3600000000000L, 3);
        
        Histogram txnHist = new Histogram(3600000000000L, 3);
        Histogram txnDbTime = new Histogram(3600000000000L, 3);
        Histogram txnProcTime = new Histogram(3600000000000L, 3);
        
        Histogram selectRetr = new Histogram(3600000000000L, 3);
        Histogram selectProc = new Histogram(3600000000000L, 3);
        Histogram selectDbTime = new Histogram(3600000000000L, 3);
        Histogram selectProcTime = new Histogram(3600000000000L, 3);

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(4, threads));

        // Run insert workload
        System.out.println("Running inserts...");
        InsertWorkload insertWorkload = new InsertWorkload(ds, rows, batch, bitpack, tenant, insertHist, insertDbTime, insertProcTime);
        pool.submit(insertWorkload).get();
        double insertElapsed = insertWorkload.getElapsedTimeSeconds();
        long insertCount = insertWorkload.getTotalInserted();
        ReportWriter.writeSummary("insert", "all", insertHist, model, 
            insertDbTime.getMean(), insertProcTime.getMean(), insertHist.getMean(), insertElapsed, insertCount, true);
        System.out.println("Insert done.");

        // Run updates
        System.out.println("Running updates (cf3 range)...");
        UpdateWorkload updateWorkload = new UpdateWorkload(ds, batch, true, bitpack, tenant, updateHist, updateDbTime, updateProcTime);
        pool.submit(updateWorkload).get();
        double updateElapsed = updateWorkload.getElapsedTimeSeconds();
        long updateCount = updateWorkload.getTotalUpdated();
        ReportWriter.writeSummary("update", "cf3", updateHist, model,
            updateDbTime.getMean(), updateProcTime.getMean(), updateHist.getMean(), updateElapsed, updateCount, true);

        // Run selects (retrieval + processing)
        System.out.println("Running selects (retrieval + processing)...");
        SelectWorkload selectWorkload = new SelectWorkload(ds, Math.max(100, rows/batch), batch, bitpack, tenant, 
            selectRetr, selectProc, selectDbTime, selectProcTime);
        pool.submit(selectWorkload).get();
        double retrievalTimeSeconds = selectWorkload.getRetrievalTimeSeconds();
        
        // RETRIEVAL: DB I/O operation - calculate throughput from retrieval time
        // Throughput represents queries per second (DB I/O throughput)
        // db_time: Actual DB query execution time
        // processing_time: Prep time before query (~0ms, just parameter setting)
        long queryCount = Math.max(100, rows/batch);
        ReportWriter.writeSummary("select", "retrieval", selectRetr, model,
            selectDbTime.getMean(), selectProcTime.getMean(), selectRetr.getMean(), retrievalTimeSeconds, queryCount, true);
        
        // PROCESSING: CPU-only conversion - do NOT calculate throughput (set to 0.0)
        // Processing is CPU-bound conversion loop, not I/O, so throughput is meaningless
        // We still track latency metrics (p50/p90/p99) to compare epoch vs bitpack conversion costs
        // db_time: 0.0 (no database involved in CPU conversion)
        // processing_time: Conversion time per row (datetime unpacking, from selectProc histogram mean)
        // total_time: Same as processing_time (no DB component)
        ReportWriter.writeSummary("select", "processing", selectProc, model,
            0.0, selectProc.getMean(), selectProc.getMean(), 0.0, 0, false);

        // Extract/groupby
        System.out.println("Running extract/groupby...");
        pool.submit(new ExtractWorkload(ds, bitpack, extractHist, extractDbTime, extractProcTime)).get();
        ReportWriter.writeSummary("extract", "groupby", extractHist, model,
            extractDbTime.getMean(), extractProcTime.getMean(), extractHist.getMean(), 0.0, 0, true);

        // Txn mixed
        System.out.println("Running txn-mixed workload...");
        pool.submit(new TxnMixedWorkload(ds, 200, bitpack, txnHist, txnDbTime, txnProcTime)).get();
        ReportWriter.writeSummary("txn_mixed", "all", txnHist, model,
            txnDbTime.getMean(), txnProcTime.getMean(), txnHist.getMean(), 0.0, 0, true);

        // Deletes
        System.out.println("Running deletes...");
        pool.submit(new DeleteWorkload(ds, batch, bitpack, deleteHist, deleteDbTime, deleteProcTime)).get();
        ReportWriter.writeSummary("delete", "all", deleteHist, model,
            deleteDbTime.getMean(), deleteProcTime.getMean(), deleteHist.getMean(), 0.0, 0, true);

        // Write final summary
        ReportWriter.writeFinalSummary(model);

        System.out.println("All workloads finished. Results in '"+Config.RESULTS_DIR+"'");
        System.out.println("Summary CSV: " + Config.RESULTS_DIR + "/summary.csv");

        pool.shutdownNow();
        DBPool.close();
    }
}
