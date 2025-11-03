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
throughput = operationCount / elapsedTimeSeconds
```
**What:** Number of operations/rows processed per second  
**Unit:** ops/sec or rows/sec, 2 decimals  
**Time Range:** Start of workload → End of workload (or specific operation time)

**By Workload:**
- **Insert**: `totalRowsInserted / insertElapsedTimeSeconds`
- **Update**: `totalRowsUpdated / updateElapsedTimeSeconds`
- **Select Retrieval**: `queryCount / retrievalTimeSeconds` ✓ (DB I/O throughput)
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
- Select: `executeQuery()` (until ResultSet ready)
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
- Select Processing: Datetime conversion (epoch/bitpack unpack), data access
- Delete: Parameter setting

**Excludes:** Database calls (executeBatch, executeQuery, etc.)

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
2. **Throughput uses actual elapsed time** (not rounded histogram values)
3. **db_time and processing_time are tracked separately** for each workload
4. **Select has two separate metrics**: Retrieval (DB query) and Processing (datetime conversion)

---

## Typical Values

| Workload | p50 | p90 | p99 | Throughput | db_time | processing_time |
|----------|-----|-----|-----|------------|---------|-----------------|
| Insert | 20-50ms | 25-60ms | 30-80ms | 30k-50k rows/s | 20-45ms | 0.5-1ms |
| Update | 10-30ms | 15-40ms | 20-60ms | 5k-15k rows/s | 8-25ms | 0-0.5ms |
| Select Retrieval | 1-10ms | 3-15ms | 10-50ms | 50-200 queries/s | 1-10ms | ~0ms |
| Select Processing | 1ms* | 1ms* | 1ms* | 0.00 (N/A)** | (shared) | <1ms |
| Delete | 100-500ms | 150-600ms | 200-800ms | 500-2k rows/s | 100-500ms | 0-0.5ms |

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

