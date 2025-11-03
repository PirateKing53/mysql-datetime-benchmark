# Datetime Storage Benchmark Suite

A comprehensive Java-based benchmark suite to compare **Epoch** vs **Bitpack** datetime storage models across **MySQL 5.7** and **PostgreSQL 9.6 with Citus** under realistic concurrent workloads.

## Features

- ✅ **Two Datetime Models**: Epoch (BIGINT milliseconds) vs Bitpack (packed datetime)
- ✅ **Two Database Systems**: MySQL 5.7 and PostgreSQL 9.6 + Citus
- ✅ **6 Workload Types**: Inserts, Updates, Reads, Extracts, Deletes, Transactions
- ✅ **Comprehensive Metrics**: p50/p90/p99 latency, throughput (from db_time), db_time, processing_time, total_time
- ✅ **CSV Reporting**: Individual workload CSVs + combined summary comparison
- ✅ **Concurrency Safe**: Deadlock retry logic, transaction isolation, range partitioning
- ✅ **Production Ready**: HikariCP connection pooling, proper error handling

## Requirements

- Java 11 (LTS)
- Maven 3.6+
- Docker (for automated database setup)
- MySQL 5.7 or PostgreSQL 9.6+ with Citus

## Quick Start

### Run All Combinations (Recommended)

```bash
cd java-benchmark
./run_full_suite.sh
```

This runs all 4 combinations automatically:
- MySQL + Epoch
- MySQL + Bitpack  
- PostgreSQL + Citus + Epoch
- PostgreSQL + Citus + Bitpack

### Filter Specific Combinations

```bash
# MySQL only (both models)
./run_full_suite.sh --mysql-only

# PostgreSQL only (both models)
./run_full_suite.sh --postgres-only

# Epoch only (both databases)
./run_full_suite.sh --epoch-only

# Bitpack only (both databases)
./run_full_suite.sh --bitpack-only
```

### Manual Run (Individual Model)

```bash
# Build project
mvn clean package -DskipTests

# Run Epoch model (MySQL - default)
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch

# Run Bitpack model (MySQL)
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model bitpack

# Run Epoch model (PostgreSQL + Citus)
java -Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb \
     -Ddb.user=postgres \
     -Ddb.pass=postgres \
     -Ddb.citus=true \
     -Dbench.results.dir=results/pg_citus_epoch \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch

# Run Bitpack model (PostgreSQL + Citus)
java -Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb \
     -Ddb.user=postgres \
     -Ddb.pass=postgres \
     -Ddb.citus=true \
     -Dbench.results.dir=results/pg_citus_bitpack \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar --model bitpack
```

## Configuration

### System Properties

```bash
# Database Connection (MySQL - default)
-Ddb.url=jdbc:mysql://127.0.0.1:3307/benchdb?rewriteBatchedStatements=true&useServerPrepStmts=true
-Ddb.user=bench
-Ddb.pass=benchpass

# Database Connection (PostgreSQL + Citus)
-Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb
-Ddb.user=postgres
-Ddb.pass=postgres
-Ddb.citus=true

# Benchmark Parameters
-Dbench.threads=8          # Thread count (default: 8)
-Dbench.rows=200000        # Total rows to insert (default: 200000)
-Dbench.batch=1000         # Batch size (default: 1000)
-Dbench.tenant=1111111     # Tenant prefix (default: 1111111)

# Results Directory
-Dbench.results.dir=results  # Default: results
```

### Command Line

```bash
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model [epoch|bitpack]
```

## Workloads

| Workload | Description | Metrics |
|----------|-------------|---------|
| **Insert** | Batch inserts with randomized datetime (2015-2025) | p50/p90/p99, throughput, db_time, processing_time, total_time |
| **Update** | Range-based updates on cf3 field | p50/p90/p99, throughput, db_time, processing_time, total_time |
| **Select** | Range queries with datetime conversion | Retrieval + Processing time separately |
| **Extract** | GROUP BY with EXTRACT functions | p50/p90/p99, throughput, db_time, processing_time, total_time |
| **Txn Mixed** | Multi-operation transactions (inserts + updates) | p50/p90/p99, throughput, db_time, processing_time, total_time |
| **Delete** | Chunked deletes with range filters | p50/p90/p99, throughput, db_time, processing_time, total_time |

## Output Format

### Summary CSV

```csv
model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time
epoch,insert,all,2.90,4.50,6.70,12400,2.10,0.80,2.90
bitpack,insert,all,2.60,4.10,5.90,13200,2.00,0.60,2.60
epoch,update,cf3,4.50,6.90,9.10,8100,3.20,0.70,3.90
bitpack,update,cf3,3.70,6.00,8.00,8700,2.90,0.60,3.50
```

### Combined Summary CSV (All Databases)

```csv
database_model,model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time
mysql_epoch,epoch,insert,all,2.90,4.50,6.70,12400,2.10,0.80,2.90
postgres_citus_epoch,epoch,insert,all,10.00,13.00,21.00,105,9.51,0.81,11.35
mysql_bitpack,bitpack,insert,all,2.60,4.10,5.90,13200,2.00,0.60,2.60
postgres_citus_bitpack,bitpack,insert,all,11.00,15.00,25.00,95,10.20,1.10,12.30
```

## Schema

### MySQL Schema

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
```

### PostgreSQL + Citus Schema

```sql
CREATE TABLE bench_common_epoch (
    id BIGSERIAL PRIMARY KEY,
    cf3 BIGINT NOT NULL,                    -- Epoch milliseconds
    tenant_module_range BIGINT NOT NULL,
    other_bigint BIGINT,
    other_decimal DECIMAL(18,4),
    other_varchar VARCHAR(255),
    other_blob BYTEA,                       -- BLOB -> BYTEA
    created_at BIGINT,
    updated_at BIGINT,
    flag_tiny SMALLINT                      -- TINYINT -> SMALLINT
);

-- Distributed table (when Citus enabled)
SELECT create_distributed_table('bench_common_epoch', 'tenant_module_range');
```

## Architecture

### Datetime Models

**Epoch Model:**
- Stores `ZonedDateTime` as `BIGINT` (milliseconds since epoch)
- Conversion: `zdt.toInstant().toEpochMilli()`
- Extraction (MySQL): `EXTRACT(YEAR FROM FROM_UNIXTIME(cf3/1000))`
- Extraction (PostgreSQL): `EXTRACT(YEAR FROM to_timestamp(cf3::numeric / 1000))`

**Bitpack Model:**
- Stores datetime fields packed in `BIGINT`
- Conversion: `Bitpack.pack(zdt, tenantPrefix)`
- Extraction: Bitwise operations `((cf3 >> 35) & 0x7FF) + 2000` (database-agnostic)

### Database Abstraction

The benchmark uses `DatabaseAdapter` to handle SQL dialect differences:
- Primary keys: `AUTO_INCREMENT` (MySQL) vs `BIGSERIAL` (PostgreSQL)
- Data types: `TINYINT` (MySQL) vs `SMALLINT` (PostgreSQL), `BLOB` (MySQL) vs `BYTEA` (PostgreSQL)
- Functions: `FROM_UNIXTIME()` (MySQL) vs `to_timestamp()` (PostgreSQL)
- UPDATE/DELETE with LIMIT: Direct (MySQL) vs Subquery (PostgreSQL)

### Concurrency Features

- **Range Partitioning**: Each thread handles unique ID ranges
- **Deadlock Retry**: Automatic retry (3 attempts, 50ms delay) on deadlock (SQLState 40001)
- **Transaction Isolation**: `READ_COMMITTED` level
- **Connection Pooling**: HikariCP with configurable pool size

## Metrics Collected

- **p50/p90/p99**: Percentile latencies (milliseconds)
- **throughput**: Rows processed per second (calculated from `db_time` only - I/O performance)
- **db_time**: Raw database execution time (milliseconds)
- **processing_time**: Java-side processing time (milliseconds)
- **total_time**: Total operation time = db_time + processing_time (milliseconds)

See [METRICS_DOCUMENTATION.md](METRICS_DOCUMENTATION.md) for detailed explanations.

## Results Organization

```
results/
├── mysql_epoch/
│   ├── summary.csv
│   ├── insert-epoch-summary.csv
│   ├── update-epoch-summary.csv
│   └── ...
├── mysql_bitpack/
│   └── ...
├── postgres_citus_epoch/
│   └── ...
├── postgres_citus_bitpack/
│   └── ...
└── combined_summary.csv  # All results merged
```

## Example Output

```
Starting benchmark with model: epoch
Starting full benchmark. threads=8 rows=200000 batch=1000 model=epoch

Running inserts...
[Epoch] insert p50=2.90ms p90=4.50ms p99=6.70ms throughput=12400 rows/s db=2.10ms proc=0.80ms total=2.90ms
Insert done.

Running updates (cf3 range)...
[Epoch] update p50=4.50ms p90=6.90ms p99=9.10ms throughput=8100 rows/s db=3.20ms proc=0.70ms total=3.90ms

Running selects (retrieval + processing)...
[Epoch] select p50=1.00ms p90=2.00ms p99=6.00ms throughput=757.58 rows/s db=1.32ms proc=0.00ms total=1.32ms
[Epoch] select p50=1.00ms p90=1.00ms p99=1.00ms throughput=N/A (CPU-only) db=0.00ms proc=1.00ms total=1.00ms

All workloads finished. Results in 'results'
Summary CSV: results/summary.csv
```

## Files Structure

```
java-benchmark/
├── src/main/java/org/bench/
│   ├── Main.java              # Entry point
│   ├── Config.java            # Configuration constants
│   ├── DatabaseAdapter.java   # Database abstraction layer
│   ├── DatabaseType.java      # Database type enum
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
├── run_full_suite.sh          # Complete benchmark automation
├── pom.xml                    # Maven dependencies
└── results/                   # Output directory
    ├── combined_summary.csv   # All results merged
    └── */                     # Per database/model results
```

## Troubleshooting

### Port Already in Use

The metrics server will automatically find an available port if 9100/9101 is in use.

### Database Connection Issues

**MySQL:**
```bash
docker ps | grep bench_mysql57
docker logs bench_mysql57
```

**PostgreSQL:**
```bash
docker ps | grep bench_postgres_citus
docker logs bench_postgres_citus
docker exec bench_postgres_citus pg_isready -U postgres
```

### Deadlock Errors

Deadlocks are automatically retried. If persistent, consider:
- Reducing thread count
- Increasing batch size
- Adjusting database configuration

### JDBC Driver Not Found

Ensure the project is built with all dependencies:
```bash
mvn clean package -DskipTests
```

The JAR includes both MySQL and PostgreSQL drivers.

## Documentation

- [HOW_TO_RUN.md](HOW_TO_RUN.md) - Detailed run instructions
- [METRICS_DOCUMENTATION.md](METRICS_DOCUMENTATION.md) - Complete metrics explanation
- [METRICS_QUICK_REFERENCE.md](METRICS_QUICK_REFERENCE.md) - Quick metrics reference
- [POSTGRESQL_CITUS_SETUP.md](POSTGRESQL_CITUS_SETUP.md) - PostgreSQL + Citus details

## License

This project is provided as-is for benchmarking purposes.
