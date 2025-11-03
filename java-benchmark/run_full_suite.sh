#!/bin/bash
# Don't exit on error - we want to run both models even if one fails
set +e

# Function to capitalize first letter (compatible with bash/zsh)
capitalize() {
    local word=$1
    local first=$(echo "$word" | cut -c1 | tr '[:lower:]' '[:upper:]')
    local rest=$(echo "$word" | cut -c2-)
    echo "${first}${rest}"
}

# Function to run benchmark with error handling
run_benchmark() {
    local model=$1
    local model_cap=$(capitalize "$model")
    echo ""
    echo "⚙️ Running $model_cap datetime model benchmark ..."
    echo "=============================================="
    java -jar "$BENCH_JAR" --model "$model"
    local exit_code=$?
    if [ $exit_code -eq 0 ]; then
        echo "✅ $model_cap benchmark completed successfully"
    else
        echo "⚠️  $model_cap benchmark exited with code $exit_code, but continuing..."
    fi
    return 0  # Always return success so script continues
}

# ---- CONFIG ----
MYSQL_VERSION=5.7
MYSQL_PORT=33306
MYSQL_ROOT_PASSWORD=admin
MYSQL_USER=admin
MYSQL_PASSWORD=admin
MYSQL_DB=benchdb
BENCH_JAR="target/bench-runner-1.0-jar-with-dependencies.jar"
THREADS=8
ROWS=200000
BATCH=1000
# ----------------

echo "🚀 Starting benchmark environment ..."

# Build the project if needed
if [ ! -f "$BENCH_JAR" ]; then
    echo "📦 Building benchmark project ..."
    mvn clean package -DskipTests
fi

# 1️⃣ Start MySQL 5.7 (if not running)
echo "🧱 Checking MySQL 5.7 ..."
if ! docker ps | grep -q mysql57; then
    echo "   Starting MySQL 5.7 container ..."
    docker rm -f mysql57 >/dev/null 2>&1 || true
    docker run -d --name mysql57 \
      -e MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD \
      -e MYSQL_DATABASE=$MYSQL_DB \
      -e MYSQL_USER=$MYSQL_USER \
      -e MYSQL_PASSWORD=$MYSQL_PASSWORD \
      -p $MYSQL_PORT:3306 \
      mysql:$MYSQL_VERSION \
      --max_connections=500 \
      --skip-log-bin \
      --innodb_buffer_pool_size=1G \
      --innodb_autoinc_lock_mode=2 \
      --innodb_lock_wait_timeout=5 \
      --innodb_flush_log_at_trx_commit=2 \
      --innodb_deadlock_detect=1
    
    echo "⏳ Waiting for MySQL to start ..."
    sleep 20
else
    echo "   MySQL already running"
fi

# Wait for MySQL to be ready
echo "   Waiting for MySQL to be ready ..."
for i in {1..30}; do
    if docker exec mysql57 mysqladmin ping -h localhost --silent 2>/dev/null; then
        break
    fi
    sleep 1
done

# Create results directory
mkdir -p results
rm -f results/summary.csv 2>/dev/null || true

# 2️⃣ Run Epoch model benchmark
run_benchmark epoch

# 3️⃣ Run Bitpack model benchmark
run_benchmark bitpack

# 4️⃣ Display summary
echo ""
echo "✅ Benchmark complete!"
echo ""
echo "📁 Reports stored in: $(pwd)/results"
echo ""
if [ -f "results/summary.csv" ]; then
    echo "📊 Summary CSV:"
    echo "==============="
    head -20 results/summary.csv | column -t -s, || cat results/summary.csv
fi
echo ""
