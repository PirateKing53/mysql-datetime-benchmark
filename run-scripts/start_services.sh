#!/bin/bash

# Parse arguments
START_POSTGRES=${1:-false}
START_ALL=false

if [ "$1" = "--all" ] || [ "$1" = "-a" ]; then
    START_ALL=true
    START_POSTGRES=true
fi

if [ "$1" = "--mysql-only" ] || [ "$1" = "-m" ]; then
    START_POSTGRES=false
fi

if [ "$1" = "--postgres-only" ] || [ "$1" = "-p" ]; then
    START_POSTGRES=true
fi

echo "========================================="
echo "Starting Benchmark Services"
echo "========================================="

# Start MySQL
echo "Starting MySQL 5.7..."
docker-compose up -d mysql57

# Start PostgreSQL + Citus if requested
if [ "$START_POSTGRES" = "true" ] || [ "$START_ALL" = "true" ]; then
    echo "Starting PostgreSQL + Citus..."
    docker-compose up -d postgres-citus
    
    # Wait for PostgreSQL to be ready
    echo "Waiting for PostgreSQL + Citus to initialize..."
    echo "Note: This may take 60-90 seconds on ARM64 (Apple Silicon) due to emulation..."
    timeout=120
    counter=0
    while ! docker exec bench_postgres_citus pg_isready -U postgres > /dev/null 2>&1; do
        sleep 3
        counter=$((counter + 3))
        if [ $counter -ge $timeout ]; then
            echo ""
            echo "ERROR: PostgreSQL did not become ready within $timeout seconds"
            docker-compose logs --tail=30 postgres-citus
            exit 1
        fi
        if [ $((counter % 15)) -eq 0 ]; then
            echo -n "($counter)"
        else
            echo -n "."
        fi
    done
    echo ""
    echo "✓ PostgreSQL + Citus is ready!"
    
    # Setup database and Citus extension
    echo "Setting up benchdb database and Citus extension..."
    sleep 3
    
    # Create database if not exists
    DB_EXISTS=$(docker exec bench_postgres_citus psql -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'benchdb'" 2>/dev/null | tr -d ' ' || echo "")
    if [ "$DB_EXISTS" != "1" ]; then
        docker exec bench_postgres_citus psql -U postgres -c "CREATE DATABASE benchdb;" || {
            echo "Warning: Database creation failed, trying again..."
            sleep 3
            docker exec bench_postgres_citus psql -U postgres -c "CREATE DATABASE benchdb;"
        }
    fi
    
    # Enable Citus extension
    docker exec bench_postgres_citus psql -U postgres -d benchdb -c "CREATE EXTENSION IF NOT EXISTS citus;" 2>/dev/null || {
        echo "Warning: Citus extension setup encountered an issue"
    }
    
    # Show Citus version
    echo ""
    echo "Citus Version:"
    docker exec bench_postgres_citus psql -U postgres -d benchdb -c "SELECT * FROM citus_version();" 2>/dev/null | grep -A 1 "citus_version" || echo "Could not retrieve Citus version"
fi

# Start monitoring services
echo ""
echo "Starting Prometheus and Grafana..."
docker-compose up -d prometheus grafana

# Wait for MySQL
if [ "$START_POSTGRES" != "true" ] || [ "$START_ALL" = "true" ]; then
    echo ""
    echo "Waiting for MySQL to be ready..."
    sleep 20
fi

echo ""
echo "========================================="
echo "Services Started Successfully!"
echo "========================================="
echo ""
echo "MySQL 5.7:"
echo "  Port: 3307"
echo "  Connection: jdbc:mysql://127.0.0.1:3307/benchdb"
echo "  User: bench / Password: benchpass"
echo ""

if [ "$START_POSTGRES" = "true" ] || [ "$START_ALL" = "true" ]; then
    echo "PostgreSQL + Citus:"
    echo "  Port: 5432"
    echo "  Connection: jdbc:postgresql://127.0.0.1:5432/benchdb"
    echo "  User: postgres / Password: postgres"
    echo ""
fi

echo "Prometheus: http://localhost:9090"
echo "Grafana: http://localhost:3000 (admin/admin)"
echo ""
echo "To run benchmarks:"
echo "  MySQL: cd java-benchmark && java -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch"
echo "  PostgreSQL+Citus: cd java-benchmark && java -Ddb.url=jdbc:postgresql://127.0.0.1:5432/benchdb -Ddb.user=postgres -Ddb.pass=postgres -Ddb.citus=true -jar target/bench-runner-1.0-jar-with-dependencies.jar --model epoch"
