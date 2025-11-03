package org.bench;

import javax.sql.DataSource;
import java.util.concurrent.*;
import org.HdrHistogram.Histogram;

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
            insertDbTime.getMean(), insertProcTime.getMean(), insertElapsed, insertCount);
        System.out.println("Insert done.");

        // Run updates
        System.out.println("Running updates (cf3 range)...");
        UpdateWorkload updateWorkload = new UpdateWorkload(ds, batch, true, bitpack, tenant, updateHist, updateDbTime, updateProcTime);
        pool.submit(updateWorkload).get();
        double updateElapsed = updateWorkload.getElapsedTimeSeconds();
        long updateCount = updateWorkload.getTotalUpdated();
        ReportWriter.writeSummary("update", "cf3", updateHist, model,
            updateDbTime.getMean(), updateProcTime.getMean(), updateElapsed, updateCount);

        // Run selects (retrieval + processing)
        System.out.println("Running selects (retrieval + processing)...");
        SelectWorkload selectWorkload = new SelectWorkload(ds, Math.max(100, rows/batch), batch, bitpack, tenant, 
            selectRetr, selectProc, selectDbTime, selectProcTime);
        pool.submit(selectWorkload).get();
        double retrievalTimeSeconds = selectWorkload.getRetrievalTimeSeconds();
        
        // RETRIEVAL: DB I/O operation - calculate throughput from retrieval time
        // Throughput represents queries per second (DB I/O throughput)
        long queryCount = Math.max(100, rows/batch);
        ReportWriter.writeSummary("select", "retrieval", selectRetr, model,
            selectDbTime.getMean(), selectProcTime.getMean(), retrievalTimeSeconds, queryCount, true);
        
        // PROCESSING: CPU-only conversion - do NOT calculate throughput (set to 0.0)
        // Processing is CPU-bound conversion loop, not I/O, so throughput is meaningless
        // We still track latency metrics (p50/p90/p99) to compare epoch vs bitpack conversion costs
        ReportWriter.writeSummary("select", "processing", selectProc, model,
            selectDbTime.getMean(), selectProcTime.getMean(), 0.0, 0, false);

        // Extract/groupby
        System.out.println("Running extract/groupby...");
        pool.submit(new ExtractWorkload(ds, bitpack, extractHist, extractDbTime, extractProcTime)).get();
        ReportWriter.writeSummary("extract", "groupby", extractHist, model,
            extractDbTime.getMean(), extractProcTime.getMean());

        // Txn mixed
        System.out.println("Running txn-mixed workload...");
        pool.submit(new TxnMixedWorkload(ds, 200, bitpack, txnHist, txnDbTime, txnProcTime)).get();
        ReportWriter.writeSummary("txn_mixed", "all", txnHist, model,
            txnDbTime.getMean(), txnProcTime.getMean());

        // Deletes
        System.out.println("Running deletes...");
        pool.submit(new DeleteWorkload(ds, batch, bitpack, deleteHist, deleteDbTime, deleteProcTime)).get();
        ReportWriter.writeSummary("delete", "all", deleteHist, model,
            deleteDbTime.getMean(), deleteProcTime.getMean());

        // Write final summary
        ReportWriter.writeFinalSummary(model);

        System.out.println("All workloads finished. Results in '"+Config.RESULTS_DIR+"'");
        System.out.println("Summary CSV: " + Config.RESULTS_DIR + "/summary.csv");

        pool.shutdownNow();
        DBPool.close();
    }
}
