# MySQL Datetime Performance Benchmark Suite

A comprehensive Java-based benchmark suite to compare **Epoch** vs **Bitpack** datetime storage models on MySQL 5.7 under realistic concurrent workloads.

## Features

- ✅ **Two Datetime Models**: Epoch (BIGINT milliseconds) vs Bitpack (packed datetime)
- ✅ **6 Workload Types**: Inserts, Updates, Reads, Extracts, Deletes, Transactions
- ✅ **Comprehensive Metrics**: p50/p90/p99 latency, throughput, db_time, processing_time
- ✅ **CSV Reporting**: Individual workload CSVs + summary comparison
- ✅ **Concurrency Safe**: Deadlock retry logic, transaction isolation, range partitioning
- ✅ **Production Ready**: HikariCP connection pooling, proper error handling

## Requirements

- Java 11 (LTS)
- Maven 3.6+
- MySQL 5.7 (local or Docker)
- Docker (for automated setup)

## Quick Start

### 1. Build the Project

```bash
cd java-benchmark
mvn clean package -DskipTests
```

### 2. Run Individual Model

```bash
# Run Epoch model benchmark
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch

# Run Bitpack model benchmark
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model bitpack
```

### 3. Run Full Suite (Both Models)

```bash
./run_full_suite.sh
```

This script will:
- Start MySQL 5.7 (Docker)
- Run both Epoch and Bitpack benchmarks
- Generate summary CSV comparison

## Configuration

### System Properties

```bash
# Thread count (default: 8)
-Dbench.threads=8

# Total rows to insert (default: 200000)
-Dbench.rows=200000

# Batch size (default: 1000)
-Dbench.batch=1000

# Tenant prefix (default: 1111111)
-Dbench.tenant=1111111

# Database connection
-Ddb.url=jdbc:mysql://127.0.0.1:33306/benchdb
-Ddb.user=admin
-Ddb.pass=admin

# Results directory (default: results)
-Dbench.results.dir=results
```

### Command Line

```bash
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch
```

## Workloads

| Workload | Description | Metrics |
|----------|-------------|---------|
| **Insert** | Batch inserts with randomized datetime (2015-2025) | p50/p90/p99, throughput, db_time, processing_time |
| **Update** | Range-based updates on cf3 field | p50/p90/p99, throughput, db_time, processing_time |
| **Select** | Range queries with datetime conversion | Retrieval + Processing time separately |
| **Extract** | GROUP BY with EXTRACT functions | p50/p90/p99, throughput, db_time, processing_time |
| **Txn Mixed** | Multi-operation transactions (inserts + updates) | p50/p90/p99, throughput, db_time, processing_time |
| **Delete** | Chunked deletes with range filters | p50/p90/p99, throughput, db_time, processing_time |

## Output Format

### Individual Workload CSV

```csv
workload,operation,p50,p90,p99,throughput,db_time,processing_time
insert,all,2.9,4.5,6.7,12400,2.1,0.8
```

### Summary CSV (`results/summary.csv`)

```csv
model,workload,operation,p50,p90,p99,throughput,db_time,processing_time
epoch,insert,all,2.9,4.5,6.7,12400,2.1,0.8
bitpack,insert,all,2.6,4.1,5.9,13200,2.0,0.6
epoch,update,cf3,4.5,6.9,9.1,8100,3.2,0.7
bitpack,update,cf3,3.7,6.0,8.0,8700,2.9,0.6
...
```

## Schema

```sql
CREATE TABLE bench_common_epoch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cf3 BIGINT NOT NULL,                    -- Epoch milliseconds
    tenant_module_range BIGINT NOT NULL,
    other_bigint BIGINT,
    other_decimal DECIMAL(18,4),
    other_varchar VARCHAR(255),
    other_blob BLOB,
    created_at BIGINT,
    updated_at BIGINT,
    flag_tiny TINYINT
) ENGINE=InnoDB;

-- Same schema for bench_common_bitpack
-- Only difference: cf3 stores bitpacked datetime
```

## Architecture

### Datetime Models

**Epoch Model:**
- Stores `ZonedDateTime` as `BIGINT` (milliseconds since epoch)
- Conversion: `zdt.toInstant().toEpochMilli()`
- Extraction: `FROM_UNIXTIME(cf3/1000)`

**Bitpack Model:**
- Stores datetime fields packed in `BIGINT`
- Conversion: `Bitpack.pack(zdt, tenantPrefix)`
- Extraction: Bitwise operations `((cf3 >> 35) & 0x7FF) + 2000`

### Concurrency Features

- **Range Partitioning**: Each thread handles unique ID ranges
- **Deadlock Retry**: Automatic retry (3 attempts, 50ms delay) on deadlock
- **Transaction Isolation**: `READ_COMMITTED` level
- **Connection Pooling**: HikariCP with configurable pool size

## Metrics Collected

- **p50/p90/p99**: Percentile latencies (milliseconds)
- **throughput**: Rows processed per second
- **db_time**: Raw database execution time (milliseconds)
- **processing_time**: Java-side processing time (milliseconds)

## Example Output

```
Starting benchmark with model: epoch
Starting full benchmark. threads=8 rows=200000 batch=1000 model=epoch

Running inserts...
[Epoch] insert p50=2.90ms p90=4.50ms p99=6.70ms throughput=12400 rows/s
Insert done.

Running updates (cf3 range)...
[Epoch] update p50=4.50ms p90=6.90ms p99=9.10ms throughput=8100 rows/s
...

All workloads finished. Results in 'results'
Summary CSV: results/summary.csv
```

## Files Structure

```
java-benchmark/
├── src/main/java/org/bench/
│   ├── Main.java              # Entry point
│   ├── Config.java            # Configuration constants
│   ├── DBPool.java            # HikariCP connection pool
│   ├── DBSetup.java           # Schema creation
│   ├── Bitpack.java           # Bitpack encode/decode
│   ├── ReportWriter.java      # CSV report generation
│   ├── DeadlockRetry.java     # Deadlock retry logic
│   ├── BenchMetrics.java      # Prometheus metrics
│   ├── InsertWorkload.java    # Insert workload
│   ├── UpdateWorkload.java    # Update workload
│   ├── SelectWorkload.java    # Select workload
│   ├── ExtractWorkload.java   # Extract/groupby workload
│   ├── TxnMixedWorkload.java  # Transactional workload
│   └── DeleteWorkload.java    # Delete workload
├── run_full_suite.sh          # Full benchmark automation
├── pom.xml                    # Maven dependencies
└── results/                   # Output directory
    ├── summary.csv            # Combined comparison
    └── *.csv                  # Individual workload results
```

## Troubleshooting

### Port Already in Use

The metrics server will automatically find an available port if 9100/9101 is in use.

### MySQL Connection Issues

Ensure MySQL is running and accessible:
```bash
docker ps | grep mysql57
```

### Deadlock Errors

Deadlocks are automatically retried. If persistent, consider:
- Reducing thread count
- Increasing batch size
- Adjusting MySQL configuration

## License

This project is provided as-is for benchmarking purposes.
