#!/bin/bash
docker-compose up -d mysql57 prometheus grafana
echo "Waiting 20 seconds for MySQL to start..."
sleep 20
echo "Services started. Grafana at http://localhost:3000 (admin/admin)."
