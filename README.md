# Benchmark Environment Suite

Complete benchmarking environment for comparing datetime storage models (Epoch vs Bitpack) across MySQL 5.7 and PostgreSQL 9.6 with Citus.

## Services

All services are managed via `docker-compose.yml`:

- **MySQL 5.7** (port 3307, user: `bench`, password: `benchpass`)
- **PostgreSQL + Citus** (port 5432, user: `postgres`, password: `postgres`)
- **Prometheus** (port 9090) - Metrics collection
- **Grafana** (port 3000, user: `admin`, password: `admin`) - Visualization

## Quick Start

### 1. Start All Services

```bash
docker-compose up -d
```

Or start individually:
```bash
docker-compose up -d mysql57          # MySQL only
docker-compose up -d postgres-citus   # PostgreSQL + Citus only
docker-compose up -d prometheus grafana  # Monitoring
```

### 2. Run Complete Benchmark Suite

```bash
cd java-benchmark
./run_full_suite.sh
```

This will automatically:
- Build the Java benchmark project
- Start required Docker services (MySQL and/or PostgreSQL)
- Run all 4 combinations:
  - MySQL + Epoch
  - MySQL + Bitpack
  - PostgreSQL + Citus + Epoch
  - PostgreSQL + Citus + Bitpack
- Generate organized CSV reports in `results/`

### 3. View Results

```bash
# Combined summary (all databases and models)
cat java-benchmark/results/combined_summary.csv

# Individual results
ls -lh java-benchmark/results/*/summary.csv
```

## Filtering Options

Run only specific combinations:

```bash
cd java-benchmark

# MySQL only (both models)
./run_full_suite.sh --mysql-only

# PostgreSQL only (both models)
./run_full_suite.sh --postgres-only

# Epoch only (both databases)
./run_full_suite.sh --epoch-only

# Bitpack only (both databases)
./run_full_suite.sh --bitpack-only
```

## Results Structure

```
java-benchmark/results/
├── mysql_epoch/
│   └── summary.csv
├── mysql_bitpack/
│   └── summary.csv
├── postgres_citus_epoch/
│   └── summary.csv
├── postgres_citus_bitpack/
│   └── summary.csv
└── combined_summary.csv  # All results merged
```

## Manual Service Management

```bash
# Start services
./run-scripts/start_services.sh --all
./run-scripts/start_services.sh --mysql-only
./run-scripts/start_services.sh --postgres-only

# Check service status
docker ps | grep bench_

# View logs
docker logs bench_mysql57
docker logs bench_postgres_citus
```

## Access Points

- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **MySQL**: `mysql -h 127.0.0.1 -P 3307 -u bench -pbenchpass benchdb`
- **PostgreSQL**: `psql -h 127.0.0.1 -p 5432 -U postgres -d benchdb`

## Documentation

- **Main Benchmark Guide**: [java-benchmark/README.md](java-benchmark/README.md)
- **How to Run**: [java-benchmark/HOW_TO_RUN.md](java-benchmark/HOW_TO_RUN.md)
- **Metrics Documentation**: [java-benchmark/METRICS_DOCUMENTATION.md](java-benchmark/METRICS_DOCUMENTATION.md)

## Troubleshooting

### Port Conflicts

If ports are already in use, modify `docker-compose.yml` to use different ports.

### Services Not Starting

```bash
# Check container status
docker ps -a | grep bench_

# View logs
docker logs bench_mysql57
docker logs bench_postgres_citus

# Restart services
docker-compose restart mysql57
docker-compose restart postgres-citus
```

### Cleanup

```bash
# Stop all services
docker-compose down

# Remove all data volumes (⚠️ deletes data)
docker-compose down -v
```
