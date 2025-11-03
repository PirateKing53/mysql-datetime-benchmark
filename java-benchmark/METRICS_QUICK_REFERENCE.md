# Metrics Quick Reference

## Formula Summary

### p50, p90, p99 (Percentiles)
```
p50 = Histogram.getValueAtPercentile(50.0)
p90 = Histogram.getValueAtPercentile(90.0)
p99 = Histogram.getValueAtPercentile(99.0)
```
**What:** Operation latency percentiles  
**Unit:** Milliseconds (ms), 2 decimals  
**Source:** HdrHistogram values (one per operation/row)

---

### throughput (Operations/Rows per Second)
```
throughput = totalOperations / totalDbTimeSeconds

Where:
totalDbTimeSeconds = (dbTimeMs_mean * totalCount) / 1000.0
```
**What:** Number of operations/rows processed per second **based on DB execution time only**  
**Unit:** ops/sec or rows/sec, 2 decimals  
**Important:** Throughput is calculated from **db_time only**, not total_time. This ensures true I/O performance comparison, excluding CPU-bound processing overhead.

**By Workload:**
- **Insert**: `totalRowsInserted / totalDbTimeSeconds` (calculated from db_time only)
- **Update**: `totalRowsUpdated / totalDbTimeSeconds` (calculated from db_time only)
- **Select Retrieval**: `queryCount / totalDbTimeSeconds` (calculated from db_time only)
- **Select Processing**: `0.0` (not calculated - CPU-only, throughput meaningless)

---

### db_time (Database Execution Time)
```
db_time = dbTimeHistogram.getMean()
dbTimeMs = (dbEndTime - dbStartTime) / 1_000_000.0
```
**What:** Average time spent in database operations  
**Unit:** Milliseconds (ms), 2 decimals  
**Measures:**
- Insert: `executeBatch() + commit()`
- Update: `executeUpdate() + commit()`
- Select Retrieval: `executeQuery()` (until ResultSet ready)
- Select Processing: **0.0** (no database involved in CPU conversion)
- Delete: `executeUpdate() + commit()`

**Excludes:** Java-side processing (data generation, datetime conversion)

---

### processing_time (Java-Side Processing Time)
```
processing_time = procTimeHistogram.getMean()
procTimeMs = (procEndTime - procStartTime) / 1_000_000.0
```
**What:** Average time spent in Java-side operations  
**Unit:** Milliseconds (ms), 2 decimals  
**Measures:**
- Insert: Data generation (random datetime, values, addBatch)
- Update: Parameter setting
- Select Retrieval: Parameter setting (~0ms, query already prepared)
- Select Processing: Datetime conversion (epoch/bitpack unpack), data access
- Delete: Parameter setting

**Excludes:** Database calls (executeBatch, executeQuery, etc.)

---

### total_time (Total Operation Time)
```
total_time = totalTimeHistogram.getMean()
total_time = db_time + processing_time (always)
```
**What:** Complete operation latency (end-to-end time)  
**Unit:** Milliseconds (ms), 2 decimals  
**Measures:** Total time from operation start to completion, including both DB and processing

**Relationship:**
- **total_time** = **db_time** + **processing_time** (always true)
- **p50/p90/p99** percentiles are calculated from **total_time** histogram
- Represents the actual latency experienced by the application

---

## Time Measurement Points

### Insert Workload
```
[procStart] → Generate data → [procEnd]
[dbStart] → executeBatch() + commit() → [dbEnd]
Total = [procStart to dbEnd] (recorded per row in batch)
```

### Update Workload
```
[procStart] → Set parameters → [procEnd]
[dbStart] → executeUpdate() + commit() → [dbEnd]
Total = [procStart to dbEnd] (recorded per update)
```

### Select Retrieval
```
[procStart] → Set parameters (~0ms) → [procEnd]
[t0] → executeQuery() → [t1]
Measures: [t0 to t1] only
```

### Select Processing
```
[procStart2] → Convert datetime + read data (per row) → [procEnd2]
Measures: [procStart2 to procEnd2] / rowCount (average per row)
```

---

## Key Notes

1. **Sub-millisecond values** are rounded UP to 1ms (affects p50/p90/p99, not throughput)
2. **Throughput is calculated from db_time only** (I/O performance, excludes processing overhead)
3. **total_time = db_time + processing_time** (always, for all workloads)
4. **p50/p90/p99 represent total_time percentiles** (from main histogram, end-to-end latency)
5. **db_time and processing_time are tracked separately** using dedicated histograms
6. **Select has two separate metrics**: Retrieval (DB query) and Processing (datetime conversion)
7. **Select Processing db_time is 0.0** (no database involved in CPU conversion phase)
8. **Unified metric model**: All workloads report p50/p90/p99/throughput/db_time/processing_time/total_time for apples-to-apples comparison

---

## Typical Values

| Workload | p50 | p90 | p99 | Throughput | db_time | processing_time | total_time |
|----------|-----|-----|-----|------------|---------|-----------------|------------|
| Insert | 20-50ms | 25-60ms | 30-80ms | 30k-50k rows/s | 20-45ms | 0.5-1ms | 20.5-46ms |
| Update | 10-30ms | 15-40ms | 20-60ms | 5k-15k rows/s | 8-25ms | 0-0.5ms | 8-25.5ms |
| Select Retrieval | 1-10ms | 3-15ms | 10-50ms | 50-200 queries/s | 1-10ms | ~0ms | 1-10ms |
| Select Processing | 1ms* | 1ms* | 1ms* | 0.00 (N/A)** | 0.00 | <1ms | <1ms |
| Delete | 100-500ms | 150-600ms | 200-800ms | 500-2k rows/s | 100-500ms | 0-0.5ms | 100-500.5ms |

*Processing shows 1ms due to rounding (actual is microseconds)  
**Throughput is 0.0 for processing - CPU-only conversion, throughput is meaningless

---

## Validation

**Processing Throughput:**
- Processing throughput is **0.0** (not calculated)
- CPU-only operations should not report throughput
- Throughput is only meaningful for I/O operations (retrieval)

**Latency Check:**
- If p50 = 1ms for processing
- This indicates sub-millisecond operations rounded up to 1ms
- Actual processing is ~3 microseconds per row ✓
- Latency comparison between epoch and bitpack is still valid

