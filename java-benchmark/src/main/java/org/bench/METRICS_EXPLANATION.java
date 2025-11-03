package org.bench;

/**
 * METRICS EXPLANATION AND CALCULATION GUIDE
 * =========================================
 * 
 * This file documents how each metric is calculated in the benchmark suite.
 * Refer to this for understanding the timing, formulas, and what each metric represents.
 */

public class METRICS_EXPLANATION {
    
    /**
     * ============================================================================
     * METRIC: p50, p90, p99 (Percentile Latencies)
     * ============================================================================
     * 
     * DEFINITION:
     *   - p50 (Median): 50% of operations complete within this time
     *   - p90: 90% of operations complete within this time
     *   - p99: 99% of operations complete within this time
     * 
     * UNIT: Milliseconds (ms), displayed with 2 decimal precision
     * 
     * CALCULATION:
     *   p50 = Histogram.getValueAtPercentile(50.0)
     *   p90 = Histogram.getValueAtPercentile(90.0)
     *   p99 = Histogram.getValueAtPercentile(99.0)
     * 
     * TIME TRACKING:
     *   - Each workload records latency values to histogram per operation/row
     *   - Values stored as milliseconds (long)
     *   - Displayed as double with 2 decimal places
     * 
     * SPECIAL NOTES:
     *   - Sub-millisecond values are rounded UP to 1ms to avoid histogram zeros
     *   - This means p50/p90/p99 may show 1ms even for faster operations
     *   - Actual throughput uses real elapsed time (not rounded histogram values)
     * 
     * WORKLOAD-SPECIFIC:
     *   - Insert: Batch time recorded for each row (represents batch operation latency)
     *   - Update: Total operation time per update (includes prep + execution)
     *   - Select Retrieval: Query execution time per query
     *   - Select Processing: Average per-row processing time (datetime conversion)
     */
    
    /**
     * ============================================================================
     * METRIC: throughput (Operations/Rows per Second)
     * ============================================================================
     * 
     * DEFINITION:
     *   Number of operations or rows processed per second during workload execution.
     * 
     * UNIT: Operations per second (ops/sec) or Rows per second (rows/sec)
     * PRECISION: 2 decimal places
     * 
     * PRIMARY FORMULA:
     *   throughput = operationCount / elapsedTimeSeconds
     * 
     * FALLBACK FORMULA (if elapsedTime not provided):
     *   totalTimeSeconds = (meanLatencyMs * totalCount) / 1000.0
     *   throughput = totalCount / totalTimeSeconds
     * 
     * TIME TRACKING:
     *   Start: workloadStartTime = System.nanoTime() (when workload.run() begins)
     *   End: workloadEndTime = System.nanoTime() (when workload.run() completes)
     *   Elapsed: (workloadEndTime - workloadStartTime) / 1_000_000_000.0 seconds
     * 
     * OPERATION COUNT BY WORKLOAD:
     *   - Insert: totalRowsInserted (total rows inserted across all batches)
     *   - Update: totalRowsUpdated (total rows updated across all iterations)
     *   - Select Retrieval: queryCount (number of queries executed)
     *   - Select Processing: totalRowsProcessed (total rows processed across all queries)
     *   - Extract: queryCount (number of extract queries executed)
     *   - Delete: totalRowsDeleted (total rows deleted)
     *   - TxnMixed: transactionCount (number of transactions completed)
     * 
     * WORKLOAD-SPECIFIC TIME RANGES:
     *   - Insert: Total elapsed time (data generation + DB insert for all rows)
     *   - Update: Total elapsed time (statement prep + DB update for all iterations)
     *   - Select Retrieval: Only retrievalTimeSeconds (DB query execution time, excludes processing)
     *   - Select Processing: Only processingTimeSeconds (datetime conversion time, excludes retrieval)
     *   - Extract: Total elapsed time (query prep + execution + result processing)
     *   - Delete: Total elapsed time (statement prep + execution)
     *   - TxnMixed: Total elapsed time per transaction (data gen + batch execution + commit)
     */
    
    /**
     * ============================================================================
     * METRIC: db_time (Database Execution Time)
     * ============================================================================
     * 
     * DEFINITION:
     *   Average time spent executing database operations only.
     *   Excludes Java-side processing (data generation, datetime conversion, etc.)
     * 
     * UNIT: Milliseconds (ms), displayed with 2 decimal precision
     * 
     * CALCULATION:
     *   db_time = dbTimeHistogram.getMean()
     * 
     *   Where each histogram entry is:
     *   dbTimeMs = (dbEndTime - dbStartTime) / 1_000_000.0  // Convert nanos to ms
     * 
     * TIME TRACKING BY WORKLOAD:
     * 
     *   INSERT WORKLOAD:
     *     Start: dbStart = System.nanoTime() (before ps.executeBatch())
     *     End: dbEnd = System.nanoTime() (after ps.executeBatch() + c.commit())
     *     Measures: executeBatch() + commit() execution time only
     *     Excludes: Data generation time (recorded separately as processing_time)
     * 
     *   UPDATE WORKLOAD:
     *     Start: dbStart = System.nanoTime() (before ps.executeUpdate())
     *     End: dbEnd = System.nanoTime() (after ps.executeUpdate() + conn.commit())
     *     Measures: executeUpdate() + commit() execution time only
     *     Excludes: Parameter setting time (recorded separately as processing_time)
     * 
     *   SELECT RETRIEVAL:
     *     Start: t0 = System.nanoTime() (before ps.executeQuery())
     *     End: t1 = System.nanoTime() (after ps.executeQuery(), ResultSet ready)
     *     Measures: executeQuery() execution time (data retrieval from database)
     *     Excludes: Row processing time (datetime conversion, etc.)
     * 
     *   SELECT PROCESSING:
     *     Note: Uses same dbTimeHist as retrieval (shared measurement)
     *     Represents: Database query execution time (same as retrieval)
     * 
     *   DELETE/EXTRACT/TXN_MIXED:
     *     Start: dbStart = System.nanoTime() (before SQL execution)
     *     End: dbEnd = System.nanoTime() (after SQL execution + commit if applicable)
     *     Measures: SQL execution time only
     */
    
    /**
     * ============================================================================
     * METRIC: processing_time (Java-Side Processing Time)
     * ============================================================================
     * 
     * DEFINITION:
     *   Average time spent in Java-side operations:
     *   - Data generation (random values, datetime creation)
     *   - Datetime conversion (epoch ↔ ZonedDateTime, bitpack pack/unpack)
     *   - Result processing (reading from ResultSet, converting values)
     *   Excludes database calls (executeBatch, executeQuery, executeUpdate, etc.)
     * 
     * UNIT: Milliseconds (ms), displayed with 2 decimal precision
     * 
     * CALCULATION:
     *   processing_time = procTimeHistogram.getMean()
     * 
     *   Where each histogram entry is:
     *   procTimeMs = (procEndTime - procStartTime) / 1_000_000.0  // Convert nanos to ms
     * 
     * TIME TRACKING BY WORKLOAD:
     * 
     *   INSERT WORKLOAD:
     *     Start: procStart = System.nanoTime() (before data generation loop)
     *     End: procEnd = System.nanoTime() (after all rows added to batch via addBatch())
     *     Measures per batch:
     *       - Random datetime generation (2015-2025 range)
     *       - Bitpack packing (if bitpack model) or epoch milliseconds
     *       - Random value generation (BIGINT, DECIMAL, VARCHAR, BLOB)
     *       - Setting PreparedStatement parameters
     *       - Calling ps.addBatch() for each row
     *     Excludes: executeBatch() + commit() (measured as db_time)
     * 
     *   UPDATE WORKLOAD:
     *     Start: procStart = System.nanoTime() (before setting parameters)
     *     End: procEnd = System.nanoTime() (after setting all parameters, before executeUpdate())
     *     Measures:
     *       - Setting PreparedStatement parameters (setLong, setInt)
     *     Excludes: executeUpdate() + commit() (measured as db_time)
     * 
     *   SELECT RETRIEVAL:
     *     Start: procStart = System.nanoTime() (before parameter setting)
     *     End: procEnd = System.nanoTime() (after parameter setting, before executeQuery())
     *     Measures: Minimal - just setting query parameters
     *     Note: Usually ~0ms as query is pre-prepared
     * 
     *   SELECT PROCESSING:
     *     Start: procStart2 = System.nanoTime() (before row processing loop)
     *     End: procEnd2 = System.nanoTime() (after all rows in ResultSet processed)
     *     Measures per query iteration:
     *       - Reading cf3 from ResultSet (getLong)
     *       - Converting to ZonedDateTime:
     *         * Epoch: Instant.ofEpochMilli(cf3) → ZonedDateTime
     *         * Bitpack: Bitpack.unpack(cf3) → ZonedDateTime
     *       - Reading other_varchar (getString)
     *       - Touching value (zdt.getYear()) to ensure conversion happens
     *     Per-row average: totalProcTimeMs / rowCount (recorded for each row)
     *     Excludes: executeQuery() time (measured as db_time in retrieval)
     * 
     *   EXTRACT WORKLOAD:
     *     Start: procStart = System.nanoTime() (before prepareStatement())
     *     End: procEnd = System.nanoTime() (after prepareStatement(), before executeQuery())
     *     Measures: Query preparation (minimal for prepared statements)
     *     Also: procStart2 to procEnd2 measures result row processing
     * 
     *   DELETE WORKLOAD:
     *     Start: procStart = System.nanoTime() (before setting parameters)
     *     End: procEnd = System.nanoTime() (after setting parameters, before executeUpdate())
     *     Measures: Parameter setting (setLong, setInt)
     * 
     *   TXN_MIXED WORKLOAD:
     *     Start: procStart = System.nanoTime() (before data generation)
     *     End: procEnd = System.nanoTime() (after all batch operations prepared)
     *     Measures per transaction:
     *       - Random datetime generation
     *       - Bitpack packing (if bitpack model)
     *       - Setting INSERT parameters (cf3, tenant_module_range, etc.)
     *       - Setting UPDATE parameters (other_varchar, id)
     *       - Calling addBatch() for inserts and updates
     *     Excludes: executeBatch() + commit() (measured as db_time)
     */
    
    /**
     * ============================================================================
     * HISTOGRAM RECORDING EXAMPLES
     * ============================================================================
     * 
     * INSERT WORKLOAD:
     *   // Total batch time (processing + DB execution)
     *   double totalTimeMs = (dbEnd - procStart) / 1_000_000.0;
     *   long timeToRecord = totalTimeMs > 0 && totalTimeMs < 1.0 ? 1L : Math.round(totalTimeMs);
     *   // Record batch time for each row in batch (represents batch operation latency)
     *   for (int i = 0; i < thisBatch; i++) {
     *       hist.recordValue(timeToRecord);
     *   }
     * 
     * UPDATE WORKLOAD:
     *   // Total operation time (parameter setting + DB execution)
     *   double totalTimeMs = (dbEnd - procStart) / 1_000_000.0;
     *   hist.recordValue(Math.round(totalTimeMs));  // Once per update operation
     * 
     * SELECT RETRIEVAL:
     *   // DB query execution time only
     *   long dbTimeMs = (t1 - t0) / 1_000_000;  // t0=before executeQuery, t1=after
     *   histRetrieval.recordValue(dbTimeMs);  // Once per query
     * 
     * SELECT PROCESSING:
     *   // Average processing time per row
     *   double totalProcTimeMs = (procEnd2 - procStart2) / 1_000_000.0;
     *   double avgProcTimeMs = totalProcTimeMs / rowCount;
     *   long timeToRecord = avgProcTimeMs > 0 && avgProcTimeMs < 1.0 ? 1L : Math.round(avgProcTimeMs);
     *   // Record average for each row processed
     *   for (int i = 0; i < rowCount; i++) {
     *       histProcessing.recordValue(timeToRecord);
     *   }
     */
    
    /**
     * ============================================================================
     * THROUGHPUT CALCULATION EXAMPLES
     * ============================================================================
     * 
     * INSERT THROUGHPUT:
     *   elapsedTimeSeconds = (workloadEndTime - workloadStartTime) / 1_000_000_000.0
     *   totalRowsInserted = sum of all rows inserted across all batches
     *   throughput = totalRowsInserted / elapsedTimeSeconds
     *   Example: 200,000 rows in 5.09 seconds = 39,298 rows/s
     * 
     * UPDATE THROUGHPUT:
     *   elapsedTimeSeconds = (workloadEndTime - workloadStartTime) / 1_000_000_000.0
     *   totalRowsUpdated = sum of all rows updated across all iterations
     *   throughput = totalRowsUpdated / elapsedTimeSeconds
     *   Example: 10,000 rows in 7.55 seconds = 1,325 rows/s
     * 
     * SELECT RETRIEVAL THROUGHPUT:
     *   retrievalTimeSeconds = totalRetrievalTimeNs / 1_000_000_000.0
     *   queryCount = iterations (number of queries executed)
     *   throughput = queryCount / retrievalTimeSeconds
     *   Example: 200 queries in 2.2 seconds = 90.91 queries/s
     * 
     * SELECT PROCESSING THROUGHPUT:
     *   processingTimeSeconds = totalProcessingTimeNs / 1_000_000_000.0
     *   totalRowsProcessed = sum of all rows processed across all queries
     *   throughput = totalRowsProcessed / processingTimeSeconds
     *   Example: 200,000 rows in 0.644 seconds = 310,547 rows/s
     *   Note: Processing is very fast (datetime conversion ~3 microseconds per row)
     */
    
    /**
     * ============================================================================
     * PRECISION AND ROUNDING
     * ============================================================================
     * 
     * TIME MEASUREMENT:
     *   - Uses System.nanoTime() for nanosecond precision
     *   - Converted to milliseconds: nanos / 1_000_000.0
     *   - Stored in histogram as long (milliseconds)
     *   - Displayed as double with 2 decimal places (%.2f)
     * 
     * ROUNDING BEHAVIOR:
     *   - Sub-millisecond values (< 1.0ms) are rounded UP to 1ms
     *   - This prevents histogram zeros for very fast operations
     *   - Affects p50/p90/p99 but NOT throughput (which uses actual elapsed time)
     * 
     * HISTOGRAM CONFIGURATION:
     *   new Histogram(3600000000000L, 3)
     *   - 3600000000000L = highest trackable value (1000 hours in milliseconds)
     *   - 3 = number of significant digits (precision)
     * 
     * OUTPUT FORMAT:
     *   - CSV: All values with 2 decimal places (%.2f)
     *   - Console: p50/p90/p99 with 2 decimals, throughput as integer or 2 decimals
     */
}

