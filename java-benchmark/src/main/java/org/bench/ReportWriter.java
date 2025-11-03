package org.bench;
import org.HdrHistogram.Histogram;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Utility class for writing benchmark results to CSV files.
 * 
 * <p>This class handles:
 * <ul>
 *   <li>Individual workload summary CSV files (per workload, per model)</li>
 *   <li>Combined summary CSV file (all workloads for a model)</li>
 *   <li>Console output of benchmark results</li>
 * </ul>
 * 
 * <p><b>CSV Format:</b>
 * <pre>
 * model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time
 * </pre>
 * 
 * <p><b>Metrics:</b>
 * <ul>
 *   <li><b>p50, p90, p99</b>: Latency percentiles in milliseconds</li>
 *   <li><b>throughput</b>: Rows per second (calculated from db_time only, not total_time)</li>
 *   <li><b>db_time</b>: Mean database execution time in milliseconds</li>
 *   <li><b>processing_time</b>: Mean Java-side processing time in milliseconds</li>
 *   <li><b>total_time</b>: Mean end-to-end operation time in milliseconds</li>
 * </ul>
 * 
 * <p><b>Throughput Calculation:</b>
 * <ul>
 *   <li>Throughput = total_operations / total_db_time_seconds</li>
 *   <li>total_db_time_seconds = (db_time_mean * total_count) / 1000.0</li>
 *   <li>For CPU-only operations (e.g., select processing), throughput is set to 0.0</li>
 * </ul>
 * 
 * @author krishna.sundar
 * @version 1.0
 */
public class ReportWriter {
    private static final String SUMMARY_CSV = "summary.csv";
    private static List<String> summaryRows = new ArrayList<>();
    
    static {
        summaryRows.add("model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time");
    }
    
    /**
     * Writes a summary with default parameters (legacy method).
     * 
     * @param workload The workload name
     * @param h The histogram containing latency metrics
     * @param model The storage model ("epoch" or "bitpack")
     */
    public static void writeSummary(String workload, Histogram h, String model) {
        writeSummary(workload, "all", h, model, 0.0, 0.0, 0.0, 0.0, 0, true);
    }
    
    /**
     * Writes a summary with db_time and processing_time (legacy method).
     * 
     * @param workload The workload name
     * @param operation The operation name (e.g., "all", "cf3", "retrieval", "processing")
     * @param h The histogram containing latency metrics
     * @param model The storage model ("epoch" or "bitpack")
     * @param dbTimeMs Mean database execution time in milliseconds
     * @param processingTimeMs Mean processing time in milliseconds
     */
    public static void writeSummary(String workload, String operation, Histogram h, String model, 
                                   double dbTimeMs, double processingTimeMs) {
        writeSummary(workload, operation, h, model, dbTimeMs, processingTimeMs, 0.0, 0.0, 0, true);
    }
    
    /**
     * Writes a summary with elapsed time and operation count (legacy method).
     * 
     * @param workload The workload name
     * @param operation The operation name
     * @param h The histogram containing latency metrics
     * @param model The storage model ("epoch" or "bitpack")
     * @param dbTimeMs Mean database execution time in milliseconds
     * @param processingTimeMs Mean processing time in milliseconds
     * @param elapsedTimeSeconds Total elapsed time in seconds
     * @param operationCount Total number of operations performed
     */
    public static void writeSummary(String workload, String operation, Histogram h, String model, 
                                   double dbTimeMs, double processingTimeMs, 
                                   double elapsedTimeSeconds, long operationCount) {
        writeSummary(workload, operation, h, model, dbTimeMs, processingTimeMs, 0.0, elapsedTimeSeconds, operationCount, true);
    }
    
    /**
     * Write summary with explicit control over throughput calculation
     * 
     * @param totalTimeMs Total time (db_time + processing_time) - from histogram mean or explicit value
     * @param calculateThroughput If false, throughput will be 0.0 (for CPU-only operations like processing)
     */
    public static void writeSummary(String workload, String operation, Histogram h, String model, 
                                   double dbTimeMs, double processingTimeMs, 
                                   double totalTimeMs,
                                   double elapsedTimeSeconds, long operationCount,
                                   boolean calculateThroughput) {
        try {
            Files.createDirectories(Paths.get(Config.RESULTS_DIR));
            
            // Calculate throughput (rows/sec) - ONLY from DB execution time, not total time
            // Throughput = operations / (sum of db_time across all operations)
            // Sum of db_time = dbTimeMs_mean * totalCount
            double throughput = 0.0;
            if (calculateThroughput) {
                if (dbTimeMs > 0 && h.getTotalCount() > 0) {
                    // Calculate total DB time: mean * count (in seconds)
                    double totalDbTimeSeconds = (dbTimeMs * h.getTotalCount()) / 1000.0;
                    if (totalDbTimeSeconds > 0) {
                        // Throughput = total operations / total DB time
                        throughput = (double)h.getTotalCount() / totalDbTimeSeconds;
                    }
                } else if (elapsedTimeSeconds > 0 && operationCount > 0) {
                    // Fallback: use elapsed time if db_time not available (shouldn't happen)
                    throughput = (double)operationCount / elapsedTimeSeconds;
                }
            }
            // If calculateThroughput is false, throughput remains 0.0 (for CPU-only operations)
            
            // Calculate total_time: use provided value or histogram mean
            double totalTime = totalTimeMs > 0 ? totalTimeMs : (h.getTotalCount() > 0 ? h.getMean() : 0.0);
            
            // Get percentiles - convert to double with proper precision
            // HdrHistogram returns double for getValueAtPercentile, but we ensure it's properly formatted
            double p50 = 0.0;
            double p90 = 0.0;
            double p99 = 0.0;
            if (h.getTotalCount() > 0) {
                p50 = h.getValueAtPercentile(50.0);
                p90 = h.getValueAtPercentile(90.0);
                p99 = h.getValueAtPercentile(99.0);
            }
            
            // Ensure db_time, processing_time, and total_time are doubles (they should already be)
            double dbTime = dbTimeMs;
            double procTime = processingTimeMs;
            
            // Write individual workload CSV with 2 decimal places
            String csvFile = Config.RESULTS_DIR + "/" + workload + "-" + model + "-summary.csv";
            try (BufferedWriter w = Files.newBufferedWriter(Paths.get(csvFile))) {
                w.write("workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time\n");
                w.write(String.format("%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n", 
                    workload, operation, p50, p90, p99, throughput, dbTime, procTime, totalTime));
            }
            
            // Add to summary with 2 decimal places
            String summaryRow = String.format("%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                model, workload, operation, p50, p90, p99, throughput, dbTime, procTime, totalTime);
            summaryRows.add(summaryRow);
            
            // Console output with 2 decimal places
            if (calculateThroughput) {
                System.out.println(String.format("[%s] %s p50=%.2fms p90=%.2fms p99=%.2fms throughput=%.2f rows/s db=%.2fms proc=%.2fms total=%.2fms",
                    model.substring(0, 1).toUpperCase() + model.substring(1), workload, p50, p90, p99, throughput, dbTime, procTime, totalTime));
            } else {
                System.out.println(String.format("[%s] %s p50=%.2fms p90=%.2fms p99=%.2fms throughput=N/A (CPU-only) db=%.2fms proc=%.2fms total=%.2fms",
                    model.substring(0, 1).toUpperCase() + model.substring(1), workload, p50, p90, p99, dbTime, procTime, totalTime));
            }
                
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Writes the final combined summary CSV file for the current model.
     * 
     * <p>Appends all summary rows for the specified model to the summary.csv file.
     * Uses exact string matching to filter rows by model name to avoid partial matches.
     * 
     * @param model The storage model ("epoch" or "bitpack")
     */
    public static void writeFinalSummary(String model) {
        try {
            Files.createDirectories(Paths.get(Config.RESULTS_DIR));
            String summaryFile = Config.RESULTS_DIR + "/" + SUMMARY_CSV;
            
            // Check if summary file exists and has header
            boolean fileExists = Files.exists(Paths.get(summaryFile));
            
            try (BufferedWriter w = Files.newBufferedWriter(Paths.get(summaryFile), 
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (!fileExists) {
                    w.write("model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time\n");
                }
                // Write all rows for this model (skip header row)
                // Use exact match to avoid partial matches (e.g., "epoch" matching "epoch_something")
                for (String row : summaryRows) {
                    // Skip header row
                    if (row.startsWith("model,")) {
                        continue;
                    }
                    // Write rows that match the current model exactly
                    // Split by comma and check first field equals model exactly
                    String[] parts = row.split(",", 2);
                    if (parts.length > 0 && parts[0].equals(model)) {
                        w.write(row);
                        w.newLine();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Resets the internal summary rows list.
     * 
     * <p>Should be called at the start of each benchmark run to clear previous results.
     */
    public static void resetSummary() {
        summaryRows.clear();
        summaryRows.add("model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time");
    }
}
