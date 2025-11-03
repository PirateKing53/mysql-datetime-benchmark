#!/bin/bash

# Run Benchmark against PostgreSQL + Citus
# Usage: ./run_benchmark_pg_citus.sh [epoch|bitpack]

set -e

MODEL=${1:-epoch}
if [ "$MODEL" != "epoch" ] && [ "$MODEL" != "bitpack" ]; then
    echo "Usage: $0 [epoch|bitpack]"
    exit 1
fi

cd "$(dirname "$0")/../java-benchmark"

# Check if JAR exists
if [ ! -f "target/bench-runner-1.0-jar-with-dependencies.jar" ]; then
    echo "JAR not found. Building..."
    mvn package -DskipTests -q
fi

# Check if PostgreSQL + Citus is running
if ! docker ps | grep -q "bench_postgres_citus"; then
    echo "ERROR: PostgreSQL + Citus container is not running"
    echo "Start it with: docker-compose up -d postgres-citus"
    exit 1
fi

# Verify connection
echo "Verifying PostgreSQL + Citus connection..."
if ! docker exec bench_postgres_citus pg_isready -U postgres > /dev/null 2>&1; then
    echo "ERROR: PostgreSQL is not ready"
    exit 1
fi

echo "========================================="
echo "Running Benchmark: PostgreSQL + Citus"
echo "Model: $MODEL"
echo "========================================="
echo ""

# Run benchmark
java -Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb \
     -Ddb.user=postgres \
     -Ddb.pass=postgres \
     -Ddb.citus=true \
     -Dbench.results.dir=results/pg_citus_${MODEL} \
     -jar target/bench-runner-1.0-jar-with-dependencies.jar --model $MODEL

echo ""
echo "========================================="
echo "Benchmark Complete!"
echo "========================================="
echo ""
echo "Results saved to: results/pg_citus_${MODEL}/"
echo "Summary CSV: results/pg_citus_${MODEL}/summary.csv"
echo ""
echo "To view results:"
echo "  cat results/pg_citus_${MODEL}/summary.csv"

