# DateTime Bitmask Storage Analysis

## Table of Contents

1. [Historical Context](#historical-context)
2. [Current Strategy: Epoch (Unix Timestamp)](#current-strategy-epoch-unix-timestamp)
3. [Proposed Strategy: Bitpack (Bitmask)](#proposed-strategy-bitpack-bitmask)
4. [Bitpack Implementation Details](#bitpack-implementation-details)
5. [Multitenancy and Indexing Considerations](#multitenancy-and-indexing-considerations)
6. [Benchmark Suite Overview](#benchmark-suite-overview)
7. [Performance Results](#performance-results)
8. [Conclusion](#conclusion)

---

## Historical Context

### Initial Strategy

In our CRM system, Date and DateTime fields were originally stored using standard database date/time types:

- **Date**: Stored as `DATE` type in `yyyy-mm-dd` format
- **DateTime**: Stored as `TIMESTAMP` type in `yyyy-mm-dd hh:mm:ss` format in UTC timezone

**Conversion Process:**
- User-provided DateTime timestamps were converted from the context user's timezone to UTC/GMT on storage
- On retrieval, UTC/GMT values were converted back to the user's timezone

### Current Strategy: Epoch (Unix Timestamp)

We migrated to storing Date and DateTime as `BIGINT` values representing milliseconds since Unix epoch (January 1, 1970).

**Date Storage:**
- Stores the date's `00:00:00` time value in UTC/GMT as a bigint
- Example: `'2025-10-01 00:00:00+00:00 (UTC/GMT)'` → `1759363200000`

**DateTime Storage:**
- User-provided DateTime is converted from context user timezone to UTC/GMT
- UTC/GMT DateTime value is converted to bigint (milliseconds since epoch)
- This final bigint value is stored in the database
- **Reverse conversion** occurs on retrieval

---

## Proposed Strategy: Bitpack (Bitmask)

### Overview

The Bitpack strategy encodes datetime components (`year`, `month`, `day`, `hour`, `minute`, `second`, `microsecond`) into specific bit positions within a 64-bit `BIGINT` integer using bitwise operations.

### Design Principles

We define a **63-bit usable mapping** (fits safely into 64-bit, leaving the sign bit unused as we only consider positive numbers).

**Bit Allocation Method:**
- Calculate the required value ranges for each datetime component
- Find the nearest power of 2 greater than the maximum expected value
- Subtract 1 to get the needed bit range
- Example: There are 12 months (1-12)
  - Binary: `0000b` (1) to `1100b` (12)
  - 4 bits needed (since `16 = 10000b` requires 5 bits)
  - Upper bound: `2^4 - 1 = 15` (we don't need to waste 16 additional values)

### Bit Layout

The datetime components are packed from **MSB (Most Significant Bit) → LSB (Least Significant Bit)** in the order: Year → Month → Day → Hour → Minute → Second → Microsecond.

| Field        | Bits | Range Supported              | Actual Range Needed |
|--------------|------|------------------------------|----------------------|
| Year         | 17   | 0 to 131,071                | More than needed     |
| Month        | 4    | 0 to 15                     | 1 to 12              |
| Day          | 5    | 0 to 31                     | 1 to 31              |
| Hour         | 5    | 0 to 31                     | 0 to 23              |
| Minute       | 6    | 0 to 63                     | 0 to 59              |
| Second       | 6    | 0 to 63                     | 0 to 59              |
| Microsecond  | 20   | 0 to 1,048,575              | 0 to 999,999         |

**Total Bits**: `17 + 4 + 5 + 5 + 6 + 6 + 20 = 63 bits`

### Bit Shifting Strategy (LSB Offsets)

Bit positions are assigned from least significant bit (LSB) upward:

- **Microseconds**: Offset 0
- **Second**: Offset 20
- **Minute**: Offset 26
- **Hour**: Offset 32
- **Day**: Offset 37
- **Month**: Offset 42
- **Year**: Offset 46

### Chronological Ordering Property

Ordering the components this way (Year → Month → Day → Hour → Minute → Second → Microsecond) ensures that **packed integer comparisons are chronological**. 

This means:
- Higher year values create larger packed numbers
- Same year, higher month → larger number
- And so on...

**Benefit**: Range queries between datetimes map directly to ranges on the packed bigint, enabling efficient range filtering without date function calls.

---

## Bitpack Implementation Details

### Packing Formula

Let `y`, `m`, `d`, `hh`, `mm`, `ss`, `ms` be the datetime components.

**Pack:**
```java
packedValue = (y << 46) | (m << 42) | (d << 37) | (hh << 32) | (mm << 26) | (ss << 20) | ms
```

**Unpack:**
```java
year  = (packedValue >> 46) & ((1 << 17) - 1)  // Mask: 0x1FFFF
month = (packedValue >> 42) & 15               // Mask: 0xF
day   = (packedValue >> 37) & 31               // Mask: 0x1F
hour  = (packedValue >> 32) & 31                // Mask: 0x1F
min   = (packedValue >> 26) & 63                // Mask: 0x3F
sec   = (packedValue >> 20) & 63                // Mask: 0x3F
ms    = packedValue & ((1 << 20) - 1)          // Mask: 0xFFFFF
```

### Implementation Notes

- All bitwise operations use unsigned right shifts (`>>>` in Java)
- Values are validated to ensure they fit within expected ranges
- Overflow protection prevents invalid datetime values

---

## Multitenancy and Indexing Considerations

### The Multitenancy Challenge

Our system uses a **multitenant data storage model** where:
- A single `BIGINT` column (`cf3`) allocated for datetime in one module/org
- The same column may store different semantic values (e.g., long numbers) in another org
- This semantic variation across tenants creates indexing challenges

### Current Production State

**Schemas do not involve indexing of datetime columns** due to multitenancy constraints.

### Can We Index? Version-Specific Analysis

#### MySQL 5.7

**Capabilities:**
- Full table indexing for columns
- **Limitation**: Does not support functional indexing

**Challenges:**
- Cannot create indexes on datetime extraction functions
- `EXTRACT(MONTH FROM FROM_UNIXTIME(cf3/1000))` must be manually computed in each query
- Full-table indexes don't work well with multitenancy (indexes span all tenants)

**Result**: Not suitable for our multitenant, function-based datetime extraction needs.

#### MySQL 8.0.13+

**Capabilities:**
- Full table indexing
- **Functional indexing** (new feature)

**Example:**
```sql
CREATE INDEX idx_cf3_month ON custom1 ((MONTH(FROM_UNIXTIME(cf3 / 1000))));
```

**Advantages:**
- Speeds up frequent `extract_month` analytical queries
- Functional indexes work on computed values

**Limitations:**
- Still has issues with multitenancy
- Semantics for the same column vary across different org ranges
- Cannot create tenant-specific functional indexes
- Global functional index may not optimize tenant-specific queries effectively

#### PostgreSQL 9.6+

**Capabilities:**
- Full table indexing
- Functional indexing
- **Conditional indexing** (partial indexes with WHERE clauses)

**Example:**
```sql
CREATE INDEX idx_cf3_month_org1111111 
ON custom1 ((EXTRACT(MONTH FROM to_timestamp(cf3 / 1000)))) 
WHERE org_id = 1111111;
```

**Advantages:**
- Functional indexing for computed datetime values
- **Conditional indexing** allows tenant-specific indexes
- Can create separate indexes per tenant with different semantics
- Solves analytical query optimization for tenant-wise queries
- Enables semantic-specific optimizations across multitenancy

**Result**: **Best solution** for our multitenant scenario with varying column semantics.

### Benchmark Design Decision: No Indexes

Despite PostgreSQL's superior indexing capabilities, this benchmark suite **intentionally omits indexes** for the following reasons:

1. **Storage Model Comparison Focus**
   - We want to measure raw storage model performance (Epoch vs Bitpack)
   - Index efficiency would mask storage model differences

2. **Fair Comparison**
   - Both models tested under identical conditions
   - No index overhead affecting either model differently

3. **Realistic Multitenant Scenario**
   - Many multitenant systems use application-level tenant filtering
   - Queries are pre-filtered by tenant before hitting the database
   - Global indexes on tenant-agnostic columns may not be beneficial

4. **Production Considerations**
   - In production, tenant-specific indexes can be added per PostgreSQL's conditional indexing
   - This benchmark provides baseline performance without indexes
   - Production systems can optimize further based on tenant-specific query patterns

---

## Benchmark Suite Overview

### Objective

Compare **Epoch** vs **Bitpack** datetime storage models across:
- **MySQL 5.7** (row storage)
- **PostgreSQL 9.6 + Citus** (columnar storage)

### Storage Models Compared

#### 1. Epoch Model
- Stores datetime as `BIGINT` milliseconds since Unix epoch
- **Java Conversion**: `ZonedDateTime.toInstant().toEpochMilli()`
- **SQL Extraction**:
  - MySQL: `EXTRACT(YEAR FROM FROM_UNIXTIME(cf3/1000))`
  - PostgreSQL: `EXTRACT(YEAR FROM to_timestamp(cf3::numeric / 1000))`
- **Advantages**: Simple, standard, easy to query
- **Disadvantages**: Requires function calls for extraction

#### 2. Bitpack Model
- Stores datetime components packed in a single `BIGINT` using bitwise operations
- **Java Conversion**: `Bitpack.pack(ZonedDateTime, tenantPrefix)`
- **SQL Extraction**:
  - MySQL: `((cf3 >> 35) & 0x7FF) + 2000` (year extraction example)
  - PostgreSQL: `((cf3 >> 35) & 2047) + 2000` (decimal equivalent)
- **Advantages**: Fast bitwise operations, no function calls
- **Disadvantages**: More complex encoding/decoding

### Database Systems

#### MySQL 5.7 (InnoDB)
- **Storage Engine**: InnoDB
- **Primary Key**: `BIGINT AUTO_INCREMENT`
- **Configuration**: Optimized for concurrent workloads
  - `innodb_autoinc_lock_mode = 2`
  - `innodb_lock_wait_timeout = 5`
  - `innodb_flush_log_at_trx_commit = 2`
  - Binary logging disabled

#### PostgreSQL 9.6 + Citus
- **Version**: PostgreSQL 9.6 with Citus extension (13.2-1)
- **Primary Key**: `BIGSERIAL`
- **Storage**: Supports both standard row storage and columnar storage
- **Data Types**: PostgreSQL-compatible (SMALLINT, BYTEA)

### Workloads

The suite includes 6 workload types:

1. **Insert**: Batch inserts with randomized datetime (2015-2025)
2. **Update**: Range-based updates on datetime field
3. **Select**: Range queries with datetime conversion (retrieval + processing phases)
4. **Extract**: GROUP BY with year extraction
5. **Transaction Mixed**: Multi-operation transactions (INSERT + UPDATE)
6. **Delete**: Chunked deletes with range filters

### Metrics Collected

- **p50, p90, p99**: Latency percentiles (milliseconds)
- **throughput**: Rows per second (calculated from DB time only)
- **db_time**: Database execution time (milliseconds)
- **processing_time**: Java-side processing time (milliseconds)
- **total_time**: End-to-end operation time (db_time + processing_time)

### Configuration

- **Threads**: 8 concurrent threads
- **Total Rows**: 200,000 rows per model
- **Batch Size**: 1,000 rows per batch
- **Date Range**: 2015-01-01 to 2025-12-31
- **Isolation Level**: `READ_COMMITTED`
- **Deadlock Retry**: 3 attempts with exponential backoff

---

## Performance Results

### Test Configuration

- **Models**: Epoch, Bitpack
- **Databases**: MySQL 5.7, PostgreSQL 9.6 + Citus
- **Combinations**: 4 total
  - `mysql_epoch`
  - `mysql_bitpack`
  - `postgres_citus_epoch`
  - `postgres_citus_bitpack`

### Key Performance Metrics

| Database         | Model   | Insert Throughput | Select Retrieval Throughput | Extract Latency (p50) |
|------------------|---------|-------------------|-----------------------------|------------------------|
| MySQL 5.7        | Epoch   | 38.86 rows/s      | 336.70 rows/s               | 98.00ms                |
| MySQL 5.7        | Bitpack | 37.16 rows/s      | 413.22 rows/s               | 76.00ms                |
| PostgreSQL+Citus | Epoch   | 101.37 rows/s     | 751.88 rows/s               | 942.00ms               |
| PostgreSQL+Citus | Bitpack | 106.55 rows/s     | 687.29 rows/s               | 208.00ms               |

### Detailed Results

#### MySQL 5.7 - Epoch

```
Workload     | Operation  | p50    | p90    | p99    | Throughput | db_time | processing_time | total_time
-------------|------------|--------|--------|--------|------------|---------|-----------------|------------
insert       | all        | 24.00  | 31.00  | 110.00 | 38.86       | 25.74   | 0.66            | 27.54
update       | cf3        | 12.00  | 749.00 | 749.00 | 2.63        | 380.00  | 0.00            | 380.50
select       | retrieval  | 2.00   | 4.00   | 40.00  | 336.70      | 2.97    | 0.00            | 2.97
select       | processing | 1.00   | 1.00   | 1.00   | 0.00        | 0.00    | 1.00            | 1.00
extract      | groupby    | 98.00  | 98.00  | 98.00  | 10.31       | 97.00   | 0.00            | 98.00
txn_mixed    | all        | 17.00  | 21.00  | 32.00  | 56.27       | 17.77   | 0.01            | 18.65
delete       | all        | 775.00 | 814.00 | 856.00 | 1.28        | 781.82  | 0.00            | 782.39
```

#### MySQL 5.7 - Bitpack

```
Workload     | Operation  | p50    | p90    | p99     | Throughput | db_time | processing_time | total_time
-------------|------------|--------|--------|---------|------------|---------|-----------------|------------
insert       | all        | 26.00  | 34.00  | 90.00   | 37.16       | 26.91   | 0.98            | 28.83
update       | cf3        | 13.00  | 781.00 | 781.00  | 2.53        | 396.00  | 0.00            | 397.00
select       | retrieval  | 2.00   | 4.00   | 17.00   | 413.22      | 2.42    | 0.00            | 2.42
select       | processing | 1.00   | 1.00   | 1.00    | 0.00        | 0.00    | 1.00            | 1.00
extract      | groupby    | 76.00  | 76.00  | 76.00   | 13.33       | 75.00   | 0.00            | 76.00
txn_mixed    | all        | 18.00  | 21.00  | 35.00   | 55.40       | 18.05   | 0.02            | 19.05
delete       | all        | 753.00 | 785.00 | 1030.00 | 1.31        | 765.63  | 0.00            | 766.20
```

#### PostgreSQL + Citus - Epoch

```
Workload     | Operation  | p50    | p90    | p99    | Throughput | db_time | processing_time | total_time
-------------|------------|--------|--------|--------|------------|---------|-----------------|------------
insert       | all        | 11.00  | 14.00  | 20.00  | 101.37      | 9.87    | 1.15            | 12.05
update       | cf3        | 25.00  | 77.00  | 77.00  | 19.61       | 51.00   | 0.00            | 51.00
select       | retrieval  | 1.00   | 2.00   | 8.00   | 751.88      | 1.33    | 0.00            | 1.33
select       | processing | 1.00   | 1.00   | 1.00   | 0.00        | 0.00    | 1.00            | 1.00
extract      | groupby    | 942.00 | 942.00 | 942.00 | 1.06        | 942.00  | 0.00            | 942.00
txn_mixed    | all        | 5.00   | 6.00   | 11.00  | 237.30      | 4.21    | 0.10            | 5.05
delete       | all        | 4.00   | 5.00   | 9.00   | 247.52      | 4.04    | 0.00            | 4.52
```

#### PostgreSQL + Citus - Bitpack

```
Workload     | Operation  | p50    | p90    | p99    | Throughput | db_time | processing_time | total_time
-------------|------------|--------|--------|--------|------------|---------|-----------------|------------
insert       | all        | 11.00  | 13.00  | 19.00  | 106.55      | 9.39    | 1.28            | 11.72
update       | cf3        | 25.00  | 77.00  | 77.00  | 19.80       | 50.50   | 0.00            | 51.00
select       | retrieval  | 1.00   | 2.00   | 9.00   | 687.29      | 1.46    | 0.00            | 1.46
select       | processing | 1.00   | 1.00   | 1.00   | 0.00        | 0.00    | 1.00            | 1.00
extract      | groupby    | 208.00 | 208.00 | 208.00 | 4.83        | 207.00  | 0.00            | 208.00
txn_mixed    | all        | 5.00   | 6.00   | 10.00  | 245.94      | 4.07    | 0.15            | 4.92
delete       | all        | 4.00   | 4.00   | 9.00   | 253.81      | 3.94    | 0.00            | 4.42
```

### Performance Analysis

#### Insert Performance
- **PostgreSQL+Citus**: ~2.6x faster than MySQL (101-106 rows/s vs 37-38 rows/s)
- **Bitpack vs Epoch**: Minimal difference in insert performance
- **Processing Overhead**: Bitpack has slightly higher Java-side cost (0.98-1.28ms vs 0.66-1.15ms)

#### Select (Retrieval) Performance
- **PostgreSQL+Citus**: Significantly faster (687-751 rows/s vs 336-413 rows/s)
- **Bitpack**: Slightly faster in MySQL (413 vs 336 rows/s), but slightly slower in PostgreSQL (687 vs 751 rows/s)

#### Extract/GroupBy Performance ⭐ **Key Finding**
- **MySQL**: Fast extract operations (76-98ms p50)
- **PostgreSQL**: Mixed performance
  - **Epoch**: Very slow (942ms p50) due to `to_timestamp()` function overhead
  - **Bitpack**: Much faster (208ms p50) - **4.5x improvement** over Epoch
- **Bitpack Advantage**: Bitwise operations significantly outperform function-based extraction

#### Update Performance
- **MySQL**: Very slow (2.5-2.6 rows/s, 380-396ms p50) - table scans without indexes
- **PostgreSQL**: Much faster (19.6-19.8 rows/s, 50-51ms p50) - better query optimizer

#### Transaction Mixed Performance
- **PostgreSQL+Citus**: ~4x faster than MySQL (237-245 rows/s vs 55-56 rows/s)
- **Processing Overhead**: Minimal difference between models

#### Delete Performance
- **PostgreSQL+Citus**: Dramatically faster (247-253 rows/s, 4ms p50)
- **MySQL**: Very slow (1.28-1.31 rows/s, 753-775ms p50) - table scans

---

## Key Insights

### 1. Storage Model Comparison

**Bitpack Advantages:**
- **Extract/GroupBy**: 4.5x faster than Epoch in PostgreSQL (208ms vs 942ms)
- **Bitwise Operations**: No function call overhead
- **Range Queries**: Chronological ordering enables efficient range filtering

**Epoch Advantages:**
- **Simplicity**: Standard, well-understood approach
- **SQL Functions**: Leverages database-native date functions
- **Lower Processing Overhead**: Slightly faster Java-side conversion

### 2. Database Comparison

**PostgreSQL + Citus:**
- Superior for most operations (inserts, selects, updates, deletes, transactions)
- 2-4x better performance for most workloads
- Extract operations benefit significantly from Bitpack model

**MySQL 5.7:**
- Competitive for extract operations (especially with Bitpack)
- Slower for updates and deletes without indexes
- Overall performance lower than PostgreSQL for most workloads

### 3. Multitenancy Implications

**No-Index Design:**
- Results reflect raw storage model performance
- Not masked by index efficiency
- Valid for scenarios with application-level tenant filtering

**Production Options:**
- **MySQL 8.0.13+**: Functional indexes can help, but multitenancy limitations remain
- **PostgreSQL**: Conditional indexing enables tenant-specific optimizations
- **This Benchmark**: Provides baseline performance for decision-making

### 4. Production Recommendations

**For Analytics Workloads:**
- **Bitpack** is strongly recommended
- Significant performance advantage in extract/groupby operations (4.5x in PostgreSQL)
- Bitwise operations avoid expensive function calls

**For General-Purpose Applications:**
- **Epoch** may be preferred for simplicity
- Standard SQL date functions provide flexibility
- Lower processing overhead

**Database Choice:**
- **PostgreSQL + Citus**: Recommended for overall performance
- **MySQL**: Acceptable for specific workloads, especially with functional indexes (8.0.13+)

---

## Conclusion

This comprehensive analysis demonstrates:

1. **Bitpack** offers significant advantages for extract/groupby operations (4.5x faster in PostgreSQL)
2. **Epoch** provides simpler, more standard datetime handling
3. **PostgreSQL + Citus** generally outperforms MySQL 5.7 for most workloads
4. **No-index design** reflects realistic multitenant scenarios
5. **Production systems** can leverage PostgreSQL's conditional indexing for tenant-specific optimizations

The benchmark suite provides production-ready metrics and comprehensive error handling, enabling informed architectural decisions for datetime storage in multitenant systems.

---

## Technical References

- **Bitpack Implementation**: See [java-benchmark/src/main/java/org/bench/Bitpack.java](https://github.com/PirateKing53/mysql-datetime-benchmark/java-benchmark/src/main/java/org/bench/Bitpack.java)
- **Benchmark Suite**: See [java-benchmark/BENCHMARK_SUMMARY.md](https://github.com/PirateKing53/mysql-datetime-benchmark/java-benchmark/BENCHMARK_SUMMARY.md)
- **Metrics Documentation**: See [java-benchmark/METRICS_DOCUMENTATION.md](https://github.com/PirateKing53/mysql-datetime-benchmark/java-benchmark/METRICS_DOCUMENTATION.md)
- **Repository**: [GitHub Repository](https://github.com/PirateKing53/mysql-datetime-benchmark)

---

*Document Version: 1.0*  
*Last Updated: 2025-11-03*  
*Benchmark Results: Based on 200,000 rows, 8 threads, 1,000 batch size*
