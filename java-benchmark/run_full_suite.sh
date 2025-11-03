#!/bin/bash
# Complete Benchmark Suite Runner
# Runs all combinations: MySQL & PostgreSQL+Citus, Epoch & Bitpack models

set +e  # Don't exit on error - run all combinations

# Function to capitalize first letter (compatible with bash/zsh)
capitalize() {
    local word=$1
    local first=$(echo "$word" | cut -c1 | tr '[:lower:]' '[:upper:]')
    local rest=$(echo "$word" | cut -c2-)
    echo "${first}${rest}"
}

# Function to run benchmark with error handling
run_benchmark() {
    local db_type=$1
    local model=$2
    local db_cap=$(capitalize "$db_type")
    local model_cap=$(capitalize "$model")
    
    echo ""
    echo "=============================================="
    echo "⚙️ Running $db_cap + $model_cap Benchmark"
    echo "=============================================="
    
    # Set results directory based on DB and model
    local results_dir="results/${db_type}_${model}"
    mkdir -p "$results_dir"
    
    # Clear previous summary for this combination
    rm -f "$results_dir/summary.csv" 2>/dev/null || true
    
    # Build Java command array based on database type
    local java_args=("java")
    
    if [ "$db_type" = "mysql" ]; then
        # MySQL - use default connection (or override with properties)
        java_args+=("-Ddb.url=jdbc:mysql://127.0.0.1:${MYSQL_PORT}/benchdb?rewriteBatchedStatements=true&useServerPrepStmts=true")
        java_args+=("-Ddb.user=${MYSQL_USER}")
        java_args+=("-Ddb.pass=${MYSQL_PASSWORD}")
    else
        # PostgreSQL + Citus
        java_args+=("-Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb")
        java_args+=("-Ddb.user=postgres")
        java_args+=("-Ddb.pass=postgres")
        java_args+=("-Ddb.citus=true")
    fi
    
    java_args+=("-Dbench.results.dir=$results_dir")
    java_args+=("-jar" "$BENCH_JAR")
    java_args+=("--model" "$model")
    
    echo "Command: ${java_args[*]}"
    echo ""
    
    "${java_args[@]}"
    local exit_code=$?
    
    if [ $exit_code -eq 0 ]; then
        echo "✅ $db_cap + $model_cap benchmark completed successfully"
    else
        echo "⚠️  $db_cap + $model_cap benchmark exited with code $exit_code, but continuing..."
    fi
    return 0  # Always return success so script continues
}

# ---- CONFIG ----
# Match docker-compose.yml settings
MYSQL_PORT=3307
MYSQL_USER=bench
MYSQL_PASSWORD=benchpass
MYSQL_DB=benchdb
BENCH_JAR="target/bench-runner-1.0-jar-with-dependencies.jar"
THREADS=8
ROWS=200000
BATCH=1000

# Parse arguments
RUN_MYSQL=true
RUN_POSTGRES=true
RUN_EPOCH=true
RUN_BITPACK=true

if [ "$1" = "--mysql-only" ]; then
    RUN_POSTGRES=false
elif [ "$1" = "--postgres-only" ]; then
    RUN_MYSQL=false
elif [ "$1" = "--epoch-only" ]; then
    RUN_BITPACK=false
elif [ "$1" = "--bitpack-only" ]; then
    RUN_EPOCH=false
fi

echo "========================================="
echo "🚀 Complete Benchmark Suite Runner"
echo "========================================="
echo ""
echo "Configuration:"
echo "  MySQL: $([ "$RUN_MYSQL" = "true" ] && echo "Yes" || echo "No")"
echo "  PostgreSQL+Citus: $([ "$RUN_POSTGRES" = "true" ] && echo "Yes" || echo "No")"
echo "  Epoch Model: $([ "$RUN_EPOCH" = "true" ] && echo "Yes" || echo "No")"
echo "  Bitpack Model: $([ "$RUN_BITPACK" = "true" ] && echo "Yes" || echo "No")"
echo ""

# Build the project if needed
cd "$(dirname "$0")"
if [ ! -f "$BENCH_JAR" ]; then
    echo "📦 Building benchmark project ..."
    mvn clean package -DskipTests
fi

# Change to java-benchmark directory
cd "$(dirname "$0")"

# Start services using docker-compose (from bench_env root)
echo "🐳 Starting Docker services ..."
cd ..

# Start MySQL if needed
if [ "$RUN_MYSQL" = "true" ]; then
    echo "🧱 Checking MySQL 5.7 ..."
    if ! docker ps | grep -q bench_mysql57; then
        echo "   Starting MySQL 5.7 via docker-compose ..."
        docker-compose up -d mysql57
        
        echo "⏳ Waiting for MySQL to be ready ..."
        for i in {1..30}; do
            if docker exec bench_mysql57 mysqladmin ping -h localhost -u root -prootpass --silent 2>/dev/null; then
                echo "   ✓ MySQL is ready"
                break
            fi
            sleep 1
        done
    else
        echo "   ✓ MySQL already running"
    fi
fi

# Start PostgreSQL + Citus if needed
if [ "$RUN_POSTGRES" = "true" ]; then
    echo "🐘 Checking PostgreSQL + Citus ..."
    if ! docker ps | grep -q bench_postgres_citus; then
        echo "   Starting PostgreSQL + Citus via docker-compose ..."
        docker-compose up -d postgres-citus
        
        echo "⏳ Waiting for PostgreSQL + Citus to initialize ..."
        echo "   (This may take 60-90 seconds on ARM64 due to emulation)"
        timeout=120
        counter=0
        while ! docker exec bench_postgres_citus pg_isready -U postgres > /dev/null 2>&1; do
            sleep 3
            counter=$((counter + 3))
            if [ $counter -ge $timeout ]; then
                echo "   ⚠️  PostgreSQL did not become ready within $timeout seconds"
                break
            fi
            if [ $((counter % 15)) -eq 0 ]; then
                echo -n "   ($counter)"
            else
                echo -n "."
            fi
        done
        echo ""
        
        if docker exec bench_postgres_citus pg_isready -U postgres > /dev/null 2>&1; then
            echo "   ✓ PostgreSQL + Citus is ready"
            
            # Setup database and Citus extension
            echo "   Setting up benchdb and Citus extension ..."
            sleep 3
            
            DB_EXISTS=$(docker exec bench_postgres_citus psql -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'benchdb'" 2>/dev/null | tr -d ' ' || echo "")
            if [ "$DB_EXISTS" != "1" ]; then
                docker exec bench_postgres_citus psql -U postgres -c "CREATE DATABASE benchdb;" 2>/dev/null || true
            fi
            
            docker exec bench_postgres_citus psql -U postgres -d benchdb -c "CREATE EXTENSION IF NOT EXISTS citus;" 2>/dev/null || true
        fi
    else
        echo "   ✓ PostgreSQL + Citus already running"
    fi
fi

# Change back to java-benchmark directory
cd java-benchmark

# Create main results directory
mkdir -p results

echo ""
echo "========================================="
echo "🏃 Running Benchmarks"
echo "========================================="
echo ""

# Run all combinations in specific order:
# 1. mysql_epoch
# 2. mysql_bitpack
# 3. postgres_citus_epoch
# 4. postgres_citus_bitpack

if [ "$RUN_MYSQL" = "true" ] && [ "$RUN_EPOCH" = "true" ]; then
    run_benchmark mysql epoch
fi

if [ "$RUN_MYSQL" = "true" ] && [ "$RUN_BITPACK" = "true" ]; then
    run_benchmark mysql bitpack
fi

if [ "$RUN_POSTGRES" = "true" ] && [ "$RUN_EPOCH" = "true" ]; then
    run_benchmark postgres_citus epoch
fi

if [ "$RUN_POSTGRES" = "true" ] && [ "$RUN_BITPACK" = "true" ]; then
    run_benchmark postgres_citus bitpack
fi

# Generate combined summary
echo ""
echo "========================================="
echo "📊 Generating Combined Summary"
echo "========================================="
echo ""
echo "Merging all 4 individual summaries into combined_summary.csv..."

# Combine all summaries into one file
COMBINED_SUMMARY="results/combined_summary.csv"
rm -f "$COMBINED_SUMMARY" 2>/dev/null || true  # Start fresh

# Create header
echo "database_model,model,workload,operation,p50,p90,p99,throughput,db_time,processing_time,total_time" > "$COMBINED_SUMMARY"

# Find all summary.csv files from the 4 combinations and append (skip header lines)
# Expected directories: mysql_epoch, mysql_bitpack, postgres_citus_epoch, postgres_citus_bitpack
for summary_file in results/mysql_epoch/summary.csv \
                    results/mysql_bitpack/summary.csv \
                    results/postgres_citus_epoch/summary.csv \
                    results/postgres_citus_bitpack/summary.csv; do
    if [ -f "$summary_file" ]; then
        # Extract database type from path (e.g., results/mysql_epoch/summary.csv -> mysql_epoch)
        db_model=$(basename $(dirname "$summary_file"))
        # Prepend database_model column and append (skip header)
        tail -n +2 "$summary_file" | sed "s/^/$db_model,/" >> "$COMBINED_SUMMARY" 2>/dev/null || true
        echo "  ✓ Added: $db_model"
    fi
done

# Also include any old summary.csv if it exists (for backward compatibility)
if [ -f "results/summary.csv" ]; then
    # Check if it's not already included in combined summary
    if ! grep -q "mysql_epoch\|mysql_bitpack\|postgres_citus" "$COMBINED_SUMMARY" 2>/dev/null; then
        tail -n +2 results/summary.csv | sed "s/^/mysql_default,/" >> "$COMBINED_SUMMARY" 2>/dev/null || true
        echo "  ✓ Added: mysql_default (legacy)"
    fi
fi

# Display results
echo ""
echo "✅ Benchmark Suite Complete!"
echo ""
echo "📁 Results stored in: $(pwd)/results"
echo ""
echo "Individual Results:"
for dir in results/*/; do
    if [ -d "$dir" ] && [ -f "${dir}summary.csv" ]; then
        echo "  - $(basename $dir)/summary.csv"
    fi
done
echo ""
if [ -f "$COMBINED_SUMMARY" ]; then
    row_count=$(tail -n +2 "$COMBINED_SUMMARY" | wc -l | tr -d " ")
    echo "📊 Combined Summary: $COMBINED_SUMMARY"
    echo "   Total rows: $row_count (from all 4 combinations)"
    echo ""
    echo "Quick Preview:"
    echo "=============="
    head -20 "$COMBINED_SUMMARY" | column -t -s, 2>/dev/null || head -20 "$COMBINED_SUMMARY"
    echo ""
    echo "📁 File location: $(pwd)/$COMBINED_SUMMARY"
    echo ""
    echo "To view full summary:"
    echo "  cat $COMBINED_SUMMARY"
    echo "  or"
    echo "  cat $COMBINED_SUMMARY | column -t -s,"
else
    echo "⚠️  Combined summary not generated (check individual summary files)"
fi
echo ""
