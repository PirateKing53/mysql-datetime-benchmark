# Benchmark Environment Suite

This archive sets up MySQL 5.7, Prometheus, and Grafana for benchmarking DateTime storage.

## Start services
```bash
docker-compose up -d mysql57 prometheus grafana
```

Wait ~20 seconds for MySQL to initialize.

Grafana: http://localhost:3000 (admin/admin)
Prometheus: http://localhost:9090
MySQL: localhost:3307 user=bench pass=benchpass

Then, drop your `java-benchmark` folder into this root and run your benchmark.

Metrics are scraped from http://host.docker.internal:8080 by default.
