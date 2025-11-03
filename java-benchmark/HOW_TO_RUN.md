# How to Run Both Models and View Results

## Quick Start (Automated)

The easiest way is to use the provided script:

```bash
cd java-benchmark
./run_full_suite.sh
```

This will:
1. Build the project (if needed)
2. Start MySQL 5.7 (Docker)
3. Run **Epoch** model benchmark
4. Run **Bitpack** model benchmark  
5. Generate combined summary CSV
6. Display the summary

## Manual Steps

### Step 1: Build the Project

```bash
cd java-benchmark
mvn clean package -DskipTests
```

### Step 2: Ensure MySQL is Running

**Option A: Use Docker (Recommended)**
```bash
docker run -d --name mysql57 \
  -e MYSQL_ROOT_PASSWORD=admin \
  -e MYSQL_DATABASE=benchdb \
  -e MYSQL_USER=admin \
  -e MYSQL_PASSWORD=admin \
  -p 33306:3306 \
  mysql:5.7 \
  --max_connections=500 \
  --skip-log-bin \
  --innodb_buffer_pool_size=1G
```

**Option B: Use Existing MySQL**
Make sure MySQL is running on port 33306 (or update `Config.java`)

### Step 3: Run Epoch Model

```bash
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch
```

You'll see output like:
```
Starting benchmark with model: epoch
Starting full benchmark. threads=8 rows=200000 batch=1000 model=epoch
Running inserts...
[Epoch] insert p50=2.90ms p90=4.50ms p99=6.70ms throughput=12400 rows/s
Insert done.
Running updates (cf3 range)...
...
All workloads finished. Results in 'results'
Summary CSV: results/summary.csv
```

### Step 4: Run Bitpack Model

```bash
java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model bitpack
```

Similar output will appear for the Bitpack model.

### Step 5: View Results

**Summary CSV (Both Models):**
```bash
cat results/summary.csv
```

**Individual Workload CSVs:**
```bash
ls -lh results/
```

**View formatted summary:**
```bash
cat results/summary.csv | column -t -s,
```

## Output Files

After running both models, you'll have:

### 1. Summary CSV (`results/summary.csv`)
Combined comparison of both models:
```csv
model,workload,operation,p50,p90,p99,throughput,db_time,processing_time
epoch,insert,all,2.9,4.5,6.7,12400,2.1,0.8
bitpack,insert,all,2.6,4.1,5.9,13200,2.0,0.6
epoch,update,cf3,4.5,6.9,9.1,8100,3.2,0.7
bitpack,update,cf3,3.7,6.0,8.0,8700,2.9,0.6
...
```

### 2. Individual Workload CSVs

For each workload and model:
- `results/insert-epoch-summary.csv`
- `results/insert-bitpack-summary.csv`
- `results/update-epoch-summary.csv`
- `results/update-bitpack-summary.csv`
- `results/select_retrieval-epoch-summary.csv`
- `results/select_retrieval-bitpack-summary.csv`
- ... and so on

## Example Complete Run

```bash
# 1. Navigate to project
cd /Users/krishna-3249/bench_env/java-benchmark

# 2. Build
mvn clean package -DskipTests

# 3. Run full suite (both models)
./run_full_suite.sh

# 4. View summary
cat results/summary.csv | column -t -s,

# 5. Or view in a text editor
open results/summary.csv
# or
vim results/summary.csv
```

## Custom Configuration

You can customize the benchmark parameters:

```bash
# With custom parameters
java -Dbench.threads=16 \
     -Dbench.rows=500000 \
     -Dbench.batch=2000 \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar \
     --model epoch
```

## Output Interpretation

### Metrics Explained

- **p50**: Median latency (50% of operations faster than this)
- **p90**: 90th percentile (90% of operations faster than this)
- **p99**: 99th percentile (99% of operations faster than this)
- **throughput**: Rows processed per second
- **db_time**: Database execution time (milliseconds)
- **processing_time**: Java-side processing time (milliseconds)

### Comparing Models

The summary CSV makes it easy to compare:
- Lower p50/p90/p99 = better latency
- Higher throughput = better performance
- Lower db_time = faster database operations
- Lower processing_time = faster Java conversions

## Troubleshooting

**Port already in use:**
- The metrics server auto-finds available ports
- Check console output for actual port used

**MySQL connection failed:**
```bash
# Check if MySQL container is running
docker ps | grep mysql57

# Check MySQL logs
docker logs mysql57
```

**No output files:**
- Check `results/` directory exists
- Verify benchmark completed successfully
- Check console for errors

