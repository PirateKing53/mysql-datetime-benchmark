package org.bench;
import org.HdrHistogram.Histogram;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ReportWriter {
    private static final String SUMMARY_CSV = "summary.csv";
    private static List<String> summaryRows = new ArrayList<>();
    
    static {
        summaryRows.add("model,workload,operation,p50,p90,p99,throughput,db_time,processing_time");
    }
    
    public static void writeSummary(String workload, Histogram h, String model) {
        writeSummary(workload, "all", h, model, 0.0, 0.0, 0.0, 0, true);
    }
    
    public static void writeSummary(String workload, String operation, Histogram h, String model, 
                                   double dbTimeMs, double processingTimeMs) {
        writeSummary(workload, operation, h, model, dbTimeMs, processingTimeMs, 0.0, 0, true);
    }
    
    public static void writeSummary(String workload, String operation, Histogram h, String model, 
                                   double dbTimeMs, double processingTimeMs, 
                                   double elapsedTimeSeconds, long operationCount) {
        writeSummary(workload, operation, h, model, dbTimeMs, processingTimeMs, elapsedTimeSeconds, operationCount, true);
    }
    
    /**
     * Write summary with explicit control over throughput calculation
     * 
     * @param calculateThroughput If false, throughput will be 0.0 (for CPU-only operations like processing)
     */
    public static void writeSummary(String workload, String operation, Histogram h, String model, 
                                   double dbTimeMs, double processingTimeMs, 
                                   double elapsedTimeSeconds, long operationCount,
                                   boolean calculateThroughput) {
        try {
            Files.createDirectories(Paths.get(Config.RESULTS_DIR));
            
            // Calculate throughput (rows/sec) - only for DB I/O operations, not CPU-only processing
            double throughput = 0.0;
            if (calculateThroughput) {
                if (elapsedTimeSeconds > 0 && operationCount > 0) {
                    // Primary: use actual elapsed time and operation count
                    throughput = (double)operationCount / elapsedTimeSeconds;
                } else if (h.getTotalCount() > 0 && h.getMean() > 0) {
                    // Fallback: estimate based on mean latency
                    // Throughput = operations / time, where time = (meanLatencyMs * totalCount) / 1000.0 seconds
                    double meanLatencyMs = h.getMean();
                    double totalTimeSeconds = (meanLatencyMs * h.getTotalCount()) / 1000.0;
                    if (totalTimeSeconds > 0) {
                        throughput = (double)h.getTotalCount() / totalTimeSeconds;
                    }
                }
            }
            // If calculateThroughput is false, throughput remains 0.0 (for CPU-only operations)
            
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
            
            // Ensure db_time and processing_time are doubles (they should already be)
            double dbTime = dbTimeMs;
            double procTime = processingTimeMs;
            
            // Write individual workload CSV with 2 decimal places
            String csvFile = Config.RESULTS_DIR + "/" + workload + "-" + model + "-summary.csv";
            try (BufferedWriter w = Files.newBufferedWriter(Paths.get(csvFile))) {
                w.write("workload,operation,p50,p90,p99,throughput,db_time,processing_time\n");
                w.write(String.format("%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n", 
                    workload, operation, p50, p90, p99, throughput, dbTime, procTime));
            }
            
            // Add to summary with 2 decimal places
            String summaryRow = String.format("%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                model, workload, operation, p50, p90, p99, throughput, dbTime, procTime);
            summaryRows.add(summaryRow);
            
            // Console output with 2 decimal places
            if (calculateThroughput) {
                System.out.println(String.format("[%s] %s p50=%.2fms p90=%.2fms p99=%.2fms throughput=%.2f rows/s",
                    model.substring(0, 1).toUpperCase() + model.substring(1), workload, p50, p90, p99, throughput));
            } else {
                System.out.println(String.format("[%s] %s p50=%.2fms p90=%.2fms p99=%.2fms throughput=N/A (CPU-only)",
                    model.substring(0, 1).toUpperCase() + model.substring(1), workload, p50, p90, p99));
            }
                
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void writeFinalSummary(String model) {
        try {
            Files.createDirectories(Paths.get(Config.RESULTS_DIR));
            String summaryFile = Config.RESULTS_DIR + "/" + SUMMARY_CSV;
            
            // Check if summary file exists and has header
            boolean fileExists = Files.exists(Paths.get(summaryFile));
            
            try (BufferedWriter w = Files.newBufferedWriter(Paths.get(summaryFile), 
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (!fileExists) {
                    w.write("model,workload,operation,p50,p90,p99,throughput,db_time,processing_time\n");
                }
                // Write all rows for this model (skip header row)
                for (String row : summaryRows) {
                    // Skip header row
                    if (row.startsWith("model,")) {
                        continue;
                    }
                    // Write rows that match the current model
                    if (row.startsWith(model + ",")) {
                        w.write(row);
                        w.newLine();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void resetSummary() {
        summaryRows.clear();
        summaryRows.add("model,workload,operation,p50,p90,p99,throughput,db_time,processing_time");
    }
}
