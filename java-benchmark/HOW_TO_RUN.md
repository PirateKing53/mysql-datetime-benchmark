# How to Run the Benchmark Suite

## Quick Start (Recommended)

Use the unified script to run all combinations:

```bash
cd java-benchmark
./run_full_suite.sh
```

This automatically:
1. Builds the project (if needed)
2. Starts Docker services (MySQL and/or PostgreSQL + Citus)
3. Runs all 4 combinations:
   - MySQL + Epoch
   - MySQL + Bitpack
   - PostgreSQL + Citus + Epoch
   - PostgreSQL + Citus + Bitpack
4. Generates organized CSV reports
5. Creates combined summary

## Filtering Options

Run only specific combinations:

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

## Manual Steps

### Step 1: Start Docker Services

From `bench_env` root:

```bash
# All services
docker-compose up -d

# Or individually
docker-compose up -d mysql57          # MySQL 5.7 (port 3307)
docker-compose up -d postgres-citus   # PostgreSQL + Citus (port 5432)
```

### Step 2: Build Project

```bash
cd java-benchmark
mvn clean package -DskipTests
```

### Step 3: Run Benchmarks

#### Option A: MySQL (Default)

```bash
# Epoch model
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch

# Bitpack model
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model bitpack
```

#### Option B: PostgreSQL + Citus

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

## Output Files

### Directory Structure

```
results/
├── mysql_epoch/
│   ├── summary.csv
│   ├── insert-epoch-summary.csv
│   ├── update-epoch-summary.csv
│   ├── select-epoch-summary.csv
│   ├── extract-epoch-summary.csv
│   ├── txn_mixed-epoch-summary.csv
│   └── delete-epoch-summary.csv
├── mysql_bitpack/
│   └── ...
├── postgres_citus_epoch/
│   └── ...
├── postgres_citus_bitpack/
│   └── ...
└── combined_summary.csv  # All results merged
```

### Summary CSV Format

**Individual Summary:**
```csv
model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time
epoch,insert,all,2.90,4.50,6.70,12400,2.10,0.80,2.90
bitpack,insert,all,2.60,4.10,5.90,13200,2.00,0.60,2.60
```

**Combined Summary:**
```csv
database_model,model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time
mysql_epoch,epoch,insert,all,2.90,4.50,6.70,12400,2.10,0.80,2.90
postgres_citus_epoch,epoch,insert,all,10.00,13.00,21.00,105,9.51,0.81,11.35
```

## View Results

```bash
# Combined summary (all databases and models)
cat results/combined_summary.csv | column -t -s,

# Individual summaries
cat results/mysql_epoch/summary.csv
cat results/postgres_citus_bitpack/summary.csv

# Compare MySQL vs PostgreSQL
diff results/mysql_epoch/summary.csv results/postgres_citus_epoch/summary.csv
```

## Custom Configuration

### Benchmark Parameters

```bash
java -Dbench.threads=16 \
     -Dbench.rows=500000 \
     -Dbench.batch=2000 \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar \
     --model epoch
```

### Database Connection

**MySQL (Custom):**
```bash
java -Ddb.url=jdbc:mysql://127.0.0.1:3307/benchdb \
     -Ddb.user=custom_user \
     -Ddb.pass=custom_pass \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar \
     --model epoch
```

**PostgreSQL (Custom):**
```bash
java -Ddb.url=jdbc:postgresql://127.0.0.1:5432/customdb \
     -Ddb.user=custom_user \
     -Ddb.pass=custom_pass \
     -Ddb.citus=true \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar \
     --model epoch
```

## Example Complete Run

```bash
# 1. Navigate to project
cd /Users/krishna-3249/bench_env/java-benchmark

# 2. Run full suite (all combinations)
./run_full_suite.sh

# 3. View combined summary
cat results/combined_summary.csv | column -t -s,

# 4. Compare specific models
diff results/mysql_epoch/summary.csv results/postgres_citus_epoch/summary.csv
```

## Metrics Interpretation

- **p50**: Median latency (50% of operations faster than this)
- **p90**: 90th percentile (90% of operations faster than this)
- **p99**: 99th percentile (99% of operations faster than this)
- **throughput**: Rows processed per second (calculated from `db_time` only)
- **db_time**: Database execution time (milliseconds)
- **processing_time**: Java-side processing time (milliseconds)
- **total_time**: Total operation time = db_time + processing_time (milliseconds)

See [METRICS_DOCUMENTATION.md](METRICS_DOCUMENTATION.md) for detailed explanations.

## Troubleshooting

### Port Already in Use

The metrics server auto-finds available ports. Check console output for actual port.

### Database Connection Failed

**MySQL:**
```bash
# Check container
docker ps | grep bench_mysql57

# Check logs
docker logs bench_mysql57

# Test connection
mysql -h 127.0.0.1 -P 3307 -u bench -pbenchpass benchdb
```

**PostgreSQL:**
```bash
# Check container
docker ps | grep bench_postgres_citus

# Check logs
docker logs bench_postgres_citus

# Test connection
docker exec bench_postgres_citus pg_isready -U postgres
docker exec bench_postgres_citus psql -U postgres -d benchdb -c "SELECT version();"
```

### No Output Files

- Check `results/` directory exists
- Verify benchmark completed successfully
- Check console for errors
- Ensure proper permissions

### JDBC Driver Not Found

Rebuild with all dependencies:
```bash
mvn clean package -DskipTests
```

The JAR includes both MySQL and PostgreSQL JDBC drivers.

### Deadlock Errors

Deadlocks are automatically retried. If persistent:
- Reduce thread count: `-Dbench.threads=4`
- Increase batch size: `-Dbench.batch=2000`
- Check database configuration
