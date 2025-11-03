# Benchmark Metrics Documentation

## Overview

This document explains all metrics collected in the benchmark suite, how they are calculated, what time periods they measure, and the formulas used.

---

## Metrics Collected

### 1. **p50, p90, p99** (Percentile Latencies)
**Unit:** Milliseconds (ms)  
**Precision:** 2 decimal places

#### Definition
- **p50 (Median)**: 50% of operations completed within this time
- **p90**: 90% of operations completed within this time  
- **p99**: 99% of operations completed within this time

#### Calculation Formula
```
p50 = Histogram.getValueAtPercentile(50.0)
p90 = Histogram.getValueAtPercentile(90.0)
p99 = Histogram.getValueAtPercentile(99.0)
```

#### Time Tracking
- **Data Source**: HdrHistogram records individual operation latencies
- **Recording**: Each workload records latency values to histogram per operation/row
- **Unit**: Values stored as milliseconds (long), displayed as double with 2 decimals

#### Notes
- Sub-millisecond values are rounded up to 1ms to avoid histogram zeros
- Values represent per-operation/row latency for that workload type

---

### 2. **throughput** (Operations/Rows per Second)
**Unit:** Operations per second (ops/sec) or Rows per second (rows/sec)  
**Precision:** 2 decimal places

#### Important: Throughput Calculated from DB Time Only
**Throughput measures database I/O capacity, NOT total operation time.**  
Throughput is calculated **only from database execution time** (`db_time`), excluding Java-side processing time. This provides true apples-to-apples comparison of I/O performance between models.

#### Calculation Formula
```
throughput = totalOperations / totalDbTimeSeconds

Where:
totalDbTimeSeconds = (dbTimeMs_mean * totalCount) / 1000.0

This ensures throughput reflects pure database I/O performance,
not CPU-bound processing overhead.
```

#### Time Tracking
- **Start Time**: `workloadStartTime = System.nanoTime()` (workload begins)
- **End Time**: `workloadEndTime = System.nanoTime()` (workload completes)
- **Elapsed Time**: `(workloadEndTime - workloadStartTime) / 1_000_000_000.0` seconds
- **Operation Count**: Total operations/rows processed during elapsed time

#### Workload-Specific Details

**Insert Workload:**
```
throughput = totalRowsInserted / insertElapsedTimeSeconds
Time Range: Start of workload → End of workload (includes data generation + DB insert)
```

**Update Workload:**
```
throughput = totalRowsUpdated / updateElapsedTimeSeconds  
Time Range: Start of workload → End of workload (includes statement prep + DB update)
```

**Select Retrieval:**
```
throughput = queryCount / retrievalTimeSeconds
Time Range: Only DB query execution time (executeQuery() start → ResultSet ready)
Operation Count: Number of queries executed
Note: Throughput is calculated (represents DB I/O throughput in queries/sec)
```

**Select Processing:**
```
throughput = 0.0 (not calculated)
Time Range: N/A (CPU-only operation, no meaningful throughput)
Operation Count: N/A
Note: Processing is CPU-bound conversion loop, throughput is meaningless.
      Only latency metrics (p50/p90/p99) are tracked to compare epoch vs bitpack conversion costs.
      Throughput is set to 0.0 to indicate this is not an I/O operation.
```

**Extract/Delete/TxnMixed:**
```
throughput = operationCount / elapsedTimeSeconds
Time Range: Start of workload → End of workload
Operation Count: Queries executed or rows processed
```

---

### 3. **db_time** (Database Execution Time)
**Unit:** Milliseconds (ms)  
**Precision:** 2 decimal places

#### Definition
Raw database execution time - time spent in database operations only (excluding Java-side processing).

#### Calculation Formula
```
db_time = dbTimeHistogram.getMean()

Where dbTimeHistogram records:
dbTime = (dbEndTime - dbStartTime) / 1_000_000.0  // nanoseconds to milliseconds
```

#### Time Tracking

**Insert Workload:**
```
Start: dbStart = System.nanoTime() (before executeBatch())
End: dbEnd = System.nanoTime() (after executeBatch() + commit())
Time Range: executeBatch() + commit() execution only
```

**Update Workload:**
```
Start: dbStart = System.nanoTime() (before executeUpdate())
End: dbEnd = System.nanoTime() (after executeUpdate() + commit())
Time Range: executeUpdate() + commit() execution only
```

**Select Retrieval:**
```
Start: t0 = System.nanoTime() (before executeQuery())
End: t1 = System.nanoTime() (after executeQuery(), ResultSet ready)
Time Range: executeQuery() execution only (data retrieval from DB)
```

**Select Processing:**
```
db_time = 0.0
Note: No database operations involved in CPU conversion phase
Time Range: N/A (pure CPU conversion, no DB I/O)
```

**Delete/Extract/TxnMixed:**
```
Start: dbStart = System.nanoTime() (before SQL execution)
End: dbEnd = System.nanoTime() (after SQL execution + commit if applicable)
Time Range: SQL execution time only
```

---

### 4. **processing_time** (Java-Side Processing Time)
**Unit:** Milliseconds (ms)  
**Precision:** 2 decimal places

#### Definition
Time spent in Java-side operations: data generation, datetime conversion, result processing (excluding database calls).

#### Calculation Formula
```
processing_time = procTimeHistogram.getMean()

Where procTimeHistogram records:
procTime = (procEndTime - procStartTime) / 1_000_000.0  // nanoseconds to milliseconds
```

#### Time Tracking

**Insert Workload:**
```
Start: procStart = System.nanoTime() (before data generation loop)
End: procEnd = System.nanoTime() (after all rows added to batch, before executeBatch())
Time Range: Data generation (datetime creation, value assignment, addBatch()) per batch
```

**Update Workload:**
```
Start: procStart = System.nanoTime() (before setting parameters)
End: procEnd = System.nanoTime() (after setting parameters, before executeUpdate())
Time Range: PreparedStatement parameter setting only
```

**Select Retrieval:**
```
Start: procStart = System.nanoTime() (before parameter setting)
End: procEnd = System.nanoTime() (after parameter setting, before executeQuery())
Time Range: Minimal - just parameter setting (query is pre-prepared)
Recorded to: procTimeHist
Used for: retrieval processing_time metric
Note: Usually ~0ms as query is already prepared
```

**Select Processing:**
```
Start: procStart2 = System.nanoTime() (before row processing loop)
End: procEnd2 = System.nanoTime() (after all rows processed)
Time Range: Per-query row processing:
  - Read cf3 value from ResultSet
  - Convert to ZonedDateTime (epoch unpack or bitpack unpack)
  - Read other_varchar
  - Touch value (zdt.getYear())
  
Per-row average calculated as: totalProcTime / rowCount
Recorded to: histProcessing (NOT procTimeHist - kept separate!)
Used for: processing processing_time metric (via histProcessing.getMean())
Note: This is the CPU conversion cost we're measuring
```

**Extract Workload:**
```
Start: procStart = System.nanoTime() (before prepareStatement())
End: procEnd = System.nanoTime() (after prepareStatement(), before executeQuery())
Time Range: Query preparation (minimal for prepared statements)
```

**Delete/TxnMixed:**
```
Start: procStart = System.nanoTime() (before setting parameters)
End: procEnd = System.nanoTime() (after setting parameters, before SQL execution)
Time Range: Parameter setting + data preparation
```

---

## Workload-Specific Metric Details

### Insert Workload

**Metrics Tracked:**
- **p50/p90/p99**: Batch insert latency (total time per batch, recorded for each row)
- **throughput**: `totalRowsInserted / insertElapsedTimeSeconds`
- **db_time**: Average time for `executeBatch() + commit()` across all batches
- **processing_time**: Average time for data generation per batch

**Timeline:**
```
[procStart] → Data Generation (random datetime, values) → [procEnd]
[dbStart] → executeBatch() + commit() → [dbEnd]
Total Batch Time = [procStart to dbEnd]
```

**Histogram Recording:**
```java
totalTimeMs = (dbEnd - procStart) / 1_000_000.0
// Record totalTimeMs for each row in batch (represents batch latency per row)
for each row: hist.recordValue(Math.round(totalTimeMs))
```

---

### Update Workload

**Metrics Tracked:**
- **p50/p90/p99**: Update operation latency (per update operation)
- **throughput**: `totalRowsUpdated / updateElapsedTimeSeconds`
- **db_time**: Average time for `executeUpdate() + commit()`
- **processing_time**: Average time for parameter setting

**Timeline:**
```
[procStart] → Set parameters (low, high, batchSize) → [procEnd]
[dbStart] → executeUpdate() + commit() → [dbEnd]
```

**Histogram Recording:**
```java
totalTimeMs = (dbEnd - procStart) / 1_000_000.0
// Record once per update operation
hist.recordValue(Math.round(totalTimeMs))
```

---

### Select Workload (Retrieval)

**Metrics Tracked:**
- **p50/p90/p99**: Query execution latency (per query)
- **throughput**: `queryCount / retrievalTimeSeconds` (queries per second)
- **db_time**: Average time for `executeQuery()` execution
- **processing_time**: Minimal (parameter setting only, usually ~0ms)

**Timeline:**
```
[procStart] → Set query parameters → [procEnd] (~0ms)
[t0] → executeQuery() → [t1] (ResultSet ready)
```

**Histogram Recording:**
```java
dbTimeMs = (t1 - t0) / 1_000_000
histRetrieval.recordValue(dbTimeMs)  // Once per query
```

---

### Select Workload (Processing)

**Metrics Tracked:**
- **p50/p90/p99**: Per-row processing latency (datetime conversion + data access)
- **throughput**: **0.0** (not calculated - CPU-only operation, throughput is meaningless)
- **db_time**: **0.0** (no database operations involved in CPU conversion)
- **processing_time**: Average time for datetime conversion per row (from `histProcessing` mean)

**Timeline:**
```
[procStart2] → For each row:
  - Read cf3 from ResultSet
  - Convert to ZonedDateTime (epoch unpack or bitpack unpack)
  - Read other_varchar
  - Touch value
→ [procEnd2]
```

**Histogram Recording:**
```java
totalProcTimeMs = (procEnd2 - procStart2) / 1_000_000.0
avgProcTimeMs = totalProcTimeMs / rowCount
// Round up sub-millisecond values to 1ms
timeToRecord = avgProcTimeMs > 0 && avgProcTimeMs < 1.0 ? 1L : Math.round(avgProcTimeMs)
// Record average for each row to histProcessing ONLY (NOT procTimeHist)
for each row: histProcessing.recordValue(timeToRecord)
// Note: procTimeHist is kept separate for retrieval prep time only
```

**Important Notes:**
- **Throughput is NOT calculated** for processing (set to 0.0)
- Processing is a CPU-bound conversion loop, not an I/O operation
- Throughput from CPU-only loops is misleading and not comparable between models
- **db_time is 0.0** for processing (no database involved in conversion phase)
- **processing_time** comes from `histProcessing.getMean()` (conversion time per row)
- Latency metrics (p50/p90/p99) are still meaningful - they show conversion cost differences
- This allows fair comparison between epoch and bitpack conversion performance
- **Key Separation**: 
  - Retrieval uses `procTimeHist` for prep time (~0ms)
  - Processing uses `histProcessing` for conversion time (what we're measuring)

---

### 5. **total_time** (Total Operation Time)
**Unit:** Milliseconds (ms)  
**Precision:** 2 decimal places

#### Definition
Complete operation latency including both database execution time and Java-side processing time. This represents the end-to-end time for each operation as experienced by the application.

#### Calculation Formula
```
total_time = totalTimeHistogram.getMean()

Where totalTimeHistogram records:
totalTime = (totalEndTime - totalStartTime) / 1_000_000.0  // nanoseconds to milliseconds

total_time = db_time + processing_time (sum of components)
```

#### Time Tracking

**Insert Workload:**
```
Start: procStart = System.nanoTime() (before data generation)
End: dbEnd = System.nanoTime() (after executeBatch() + commit())
Time Range: Complete operation from data generation through DB commit
total_time = processing_time + db_time
```

**Update Workload:**
```
Start: procStart = System.nanoTime() (before parameter setting)
End: dbEnd = System.nanoTime() (after executeUpdate() + commit())
Time Range: Complete operation from parameter setting through DB commit
total_time = processing_time + db_time
```

**Select Retrieval:**
```
Start: procStart = System.nanoTime() (before parameter setting)
End: t1 = System.nanoTime() (after executeQuery(), ResultSet ready)
Time Range: Complete operation from parameter setting through query execution
total_time = processing_time + db_time
```

**Select Processing:**
```
Start: procStart2 = System.nanoTime() (before row processing loop)
End: procEnd2 = System.nanoTime() (after all rows processed)
Time Range: Complete row conversion and processing
total_time = processing_time (db_time = 0.0 for CPU-only operations)
```

**Delete/Extract/TxnMixed:**
```
Start: procStart = System.nanoTime() (before parameter setting/prep)
End: dbEnd = System.nanoTime() (after SQL execution + commit if applicable)
Time Range: Complete operation from prep through DB execution
total_time = processing_time + db_time
```

#### Relationship to Other Metrics
- **total_time** = **db_time** + **processing_time** (always)
- **p50/p90/p99** represent percentiles of **total_time** (from main histogram)
- **throughput** is calculated from **db_time** only (I/O performance)

---

### Extract Workload

**Metrics Tracked:**
- **p50/p90/p99**: Query execution latency (GROUP BY with EXTRACT)
- **throughput**: Calculated from histogram fallback formula
- **db_time**: Average time for `executeQuery()` with GROUP BY
- **processing_time**: Average time for query preparation + result processing

**Timeline:**
```
[procStart] → prepareStatement() → [procEnd]
[t0] → executeQuery() (GROUP BY EXTRACT) → [t1]
[procStart2] → Process result rows → [procEnd2]
```

---

### Delete Workload

**Metrics Tracked:**
- **p50/p90/p99**: Delete operation latency (per delete batch)
- **throughput**: Calculated from histogram fallback formula
- **db_time**: Average time for `executeUpdate() + commit()` per delete
- **processing_time**: Average time for parameter setting

**Timeline:**
```
[procStart] → Set parameters → [procEnd]
[dbStart] → executeUpdate() + commit() → [dbEnd]
Total = [procStart to dbEnd]
```

---

### TxnMixed Workload

**Metrics Tracked:**
- **p50/p90/p99**: Transaction latency (inserts + updates per transaction)
- **throughput**: Calculated from histogram fallback formula
- **db_time**: Average time for batch execution (`executeBatch()` for inserts + updates)
- **processing_time**: Average time for data generation and batch preparation

**Timeline:**
```
[procStart] → Generate data for all ops in txn → [procEnd]
[dbStart] → executeBatch(inserts) + executeBatch(updates) + commit() → [dbEnd]
Total Transaction = [totalStart to totalEnd]
```

---

## Time Measurement Details

### Precision
- **Nanosecond precision** for timing (`System.nanoTime()`)
- **Millisecond storage** in histograms (converted: `nanos / 1_000_000`)
- **Double precision** for calculations and final output
- **2 decimal places** in CSV and console output

### Rounding Behavior
- Sub-millisecond values (< 1.0ms) are rounded **up** to 1ms to avoid zeros in histogram
- This affects p50/p90/p99 but not throughput (which uses actual elapsed time)

### Histogram Configuration
```java
new Histogram(3600000000000L, 3)
// 3600000000000L = highest trackable value (1000 hours in milliseconds)
// 3 = number of significant digits (precision)
```

---

## CSV Output Format

### Individual Workload CSV
```
workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time
```

### Summary CSV
```
model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time,total_time
```

### Example Row
```
epoch,insert,all,25.30,28.50,35.20,39298.32,23.62,0.55
```

**Interpretation:**
- Model: epoch
- Workload: insert
- Operation: all
- p50: 25.30ms (median latency)
- p90: 28.50ms (90th percentile)
- p99: 35.20ms (99th percentile)
- throughput: 39,298.32 rows/second
- db_time: 23.62ms (average database execution)
- processing_time: 0.55ms (average Java-side processing)

---

## Validation Notes

### Throughput Validation
- **Insert**: 30k-50k rows/s is typical for batch inserts (1000 rows/batch)
- **Update**: 5k-15k rows/s depending on update complexity
- **Select Processing**: 100k-500k rows/s for simple datetime conversion (very fast)
- **Select Retrieval**: 50-200 queries/s depending on query complexity

### Latency Validation
- **Insert batches**: 20-50ms per batch (1000 rows) = 0.02-0.05ms per row
- **Update**: 10-50ms per update operation
- **Select Retrieval**: 1-10ms per query (depends on data size and indexes)
- **Select Processing**: <1ms per row (microseconds, rounded to 1ms)

---

## Key Implementation Notes

1. **Separate Time Tracking**: Each workload tracks `db_time` and `processing_time` separately
2. **Elapsed Time**: Workloads track total elapsed time for accurate throughput calculation
3. **Operation Counts**: Each workload tracks total operations/rows processed
4. **Histogram Precision**: Sub-millisecond operations are rounded up to avoid zeros
5. **Throughput Accuracy**: Uses actual elapsed time, not histogram values (which may be rounded)

---

## Formulas Summary

```
// Percentiles
p50 = Histogram.getValueAtPercentile(50.0)
p90 = Histogram.getValueAtPercentile(90.0)  
p99 = Histogram.getValueAtPercentile(99.0)

// Throughput (primary)
throughput = operationCount / elapsedTimeSeconds

// Throughput (fallback)
totalTimeSeconds = (meanLatencyMs * totalCount) / 1000.0
throughput = totalCount / totalTimeSeconds

// Database Time
db_time = dbTimeHistogram.getMean()
dbTime = (dbEnd - dbStart) / 1_000_000.0  // nanos to ms

// Processing Time  
processing_time = procTimeHistogram.getMean()
procTime = (procEnd - procStart) / 1_000_000.0  // nanos to ms
```

