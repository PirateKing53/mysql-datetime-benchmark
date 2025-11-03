# PostgreSQL + Citus Setup and Configuration

This document explains PostgreSQL + Citus integration, SQL compatibility, and configuration details.

## Overview

The benchmark suite supports PostgreSQL 9.6+ with Citus extension for distributed and columnar storage testing.

## Docker Setup

PostgreSQL + Citus is managed via the main `docker-compose.yml`:

```bash
# Start PostgreSQL + Citus
docker-compose up -d postgres-citus

# Check status
docker exec bench_postgres_citus pg_isready -U postgres

# View logs
docker logs bench_postgres_citus
```

**Container Details:**
- **Name**: `bench_postgres_citus`
- **Image**: `citusdata/citus:latest`
- **Port**: `5432` (host) → `5432` (container)
- **User**: `postgres`
- **Password**: `postgres`
- **Database**: `benchdb`

## Running Benchmarks

### Quick Run (All Combinations)

```bash
cd java-benchmark
./run_full_suite.sh
```

This includes PostgreSQL + Citus benchmarks automatically.

### Manual Run

```bash
# Epoch model
java -Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb \
     -Ddb.user=postgres \
     -Ddb.pass=postgres \
     -Ddb.citus=true \
     -Dbench.results.dir=results/pg_citus_epoch \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch

# Bitpack model
java -Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb \
     -Ddb.user=postgres \
     -Ddb.pass=postgres \
     -Ddb.citus=true \
     -Dbench.results.dir=results/pg_citus_bitpack \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar --model bitpack
```

## SQL Compatibility

The benchmark uses `DatabaseAdapter` to handle SQL dialect differences automatically.

### 1. Primary Keys

**MySQL:**
```sql
id BIGINT AUTO_INCREMENT PRIMARY KEY
```

**PostgreSQL:**
```sql
id BIGSERIAL PRIMARY KEY
```

### 2. Data Types

**MySQL → PostgreSQL:**
- `TINYINT` → `SMALLINT`
- `BLOB` → `BYTEA`

### 3. Timestamp Conversion

**MySQL:**
```sql
FROM_UNIXTIME(cf3 / 1000)
```

**PostgreSQL:**
```sql
to_timestamp(cf3::numeric / 1000)
```

### 4. Year Extraction

**MySQL Epoch:**
```sql
EXTRACT(YEAR FROM FROM_UNIXTIME(cf3/1000))
```

**PostgreSQL Epoch:**
```sql
EXTRACT(YEAR FROM to_timestamp(cf3::numeric / 1000))
```

**Bitpack:**
- **MySQL**: `((cf3 >> 35) & 0x7FF) + 2000` (uses hex literal)
- **PostgreSQL**: `((cf3 >> 35) & 2047) + 2000` (uses decimal, 2047 = 0x7FF)

### 5. UPDATE/DELETE with LIMIT

**MySQL (Direct):**
```sql
UPDATE bench_common_epoch SET ... WHERE ... LIMIT ?
DELETE FROM bench_common_epoch WHERE ... LIMIT ?
```

**PostgreSQL (Subquery):**
```sql
UPDATE bench_common_epoch SET ... WHERE id IN (
    SELECT id FROM bench_common_epoch WHERE ... LIMIT ?
)
DELETE FROM bench_common_epoch WHERE id IN (
    SELECT id FROM bench_common_epoch WHERE ... LIMIT ?
)
```

### 6. GROUP BY

**MySQL (Allows alias):**
```sql
SELECT EXTRACT(...) as yr, COUNT(*) as cnt
FROM ...
GROUP BY yr
```

**PostgreSQL (Requires full expression):**
```sql
SELECT EXTRACT(...) as yr, COUNT(*) as cnt
FROM ...
GROUP BY EXTRACT(...)
```

## Citus Features

### Distributed Tables

When `-Ddb.citus=true`, the benchmark automatically:

1. Creates tables (standard PostgreSQL syntax)
2. Distributes tables:
   ```sql
   SELECT create_distributed_table('bench_common_epoch', 'tenant_module_range');
   SELECT create_distributed_table('bench_common_bitpack', 'tenant_module_range');
   ```

### Columnar Storage

For Citus 11.0+ with columnar support:

```sql
ALTER TABLE bench_common_epoch SET (columnar = true);
ALTER TABLE bench_common_bitpack SET (columnar = true);
```

**Note:** The current benchmark uses standard Citus tables. Columnar storage can be enabled manually if needed.

### Single Node Setup

The Docker setup uses a single-node Citus installation (for development/testing). Production deployments require multiple worker nodes for true distribution.

## Connection Properties

### HikariCP Configuration

PostgreSQL-specific connection properties:

```java
config.addDataSourceProperty("reWriteBatchedInserts", "true");
```

This enables batch insert rewriting for better PostgreSQL performance.

### Connection String

```java
jdbc:postgresql://127.0.0.1:5432/benchdb
```

## Automatic Setup

The benchmark automatically:

1. **Creates database** (if not exists):
   ```sql
   CREATE DATABASE benchdb;
   ```

2. **Enables Citus extension**:
   ```sql
   CREATE EXTENSION IF NOT EXISTS citus;
   ```

3. **Creates tables** with PostgreSQL-compatible syntax

4. **Distributes tables** (if `-Ddb.citus=true`)

5. **Tests connection** before running workloads

## Verification

### Check Citus Extension

```bash
docker exec bench_postgres_citus psql -U postgres -d benchdb \
  -c "SELECT * FROM pg_extension WHERE extname = 'citus';"
```

### Check Citus Version

```bash
docker exec bench_postgres_citus psql -U postgres -d benchdb \
  -c "SELECT * FROM citus_version();"
```

### Check Distributed Tables

```bash
docker exec bench_postgres_citus psql -U postgres -d benchdb \
  -c "SELECT * FROM citus_tables;"
```

## Troubleshooting

### Citus Extension Not Found

```bash
# Manually enable
docker exec bench_postgres_citus psql -U postgres -d benchdb \
  -c "CREATE EXTENSION IF NOT EXISTS citus;"
```

### Distributed Table Creation Fails

The benchmark creates tables before distribution. If distribution fails:
- Check Citus extension is enabled
- Verify table exists: `SELECT * FROM bench_common_epoch LIMIT 1;`
- Check distribution column exists: `tenant_module_range`

### Connection Refused

```bash
# Check container is running
docker ps | grep bench_postgres_citus

# Check logs
docker logs bench_postgres_citus

# Test connection
docker exec bench_postgres_citus pg_isready -U postgres
```

### Port Conflicts

If port 5432 is in use, modify `docker-compose.yml`:

```yaml
ports:
  - "5433:5432"  # Use different host port
```

Then update connection URL:
```bash
-Ddb.url=jdbc:postgresql://127.0.0.1:5433/benchdb
```

## Performance Notes

- **Single Node**: Docker setup uses single node (for testing). Production requires multiple workers
- **Columnar Storage**: Optimized for analytics/OLAP workloads (read-heavy)
- **Standard Tables**: Better for OLTP workloads (mixed read/write)
- **Connection Pooling**: HikariCP optimizes connection reuse
- **Batch Inserts**: PostgreSQL batch rewriting improves insert performance

## Comparison with MySQL

Both databases support the same workload types:
- Insert, Update, Select, Extract, Delete, Transactional

Results are directly comparable:
- Same metrics (p50/p90/p99, throughput, db_time, processing_time, total_time)
- Same workload patterns
- Same concurrency settings

Differences:
- SQL syntax (handled by `DatabaseAdapter`)
- Performance characteristics
- Index strategies (MySQL InnoDB vs PostgreSQL B-tree)

## See Also

- [README.md](README.md) - Main documentation
- [HOW_TO_RUN.md](HOW_TO_RUN.md) - Run instructions
- [METRICS_DOCUMENTATION.md](METRICS_DOCUMENTATION.md) - Metrics explanation
