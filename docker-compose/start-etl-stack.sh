#!/bin/bash

set -e  # Exit immediately if a command exits with a non-zero status

# Step 1: Start only required services first (without webserver)
echo "🚀 Starting HDFS, Postgres, and Airflow DB services..."
docker compose -f docker-compose.etl.yml up -d airflow-db etl-db namenode datanode airflow-scheduler

# Step 2: Initialize the Airflow metadata database
echo "🔄 Initializing Airflow metadata DB..."
docker exec airflow-scheduler airflow db init

# Step 3: Now start the Airflow webserver
echo "🚀 Starting Airflow webserver..."
docker compose -f docker-compose.etl.yml up -d airflow-webserver

# Step 4: Wait until Airflow Webserver is reachable
echo "⏳ Waiting for Airflow webserver to be ready..."
MAX_WAIT=120
WAITED=0
INTERVAL=5

until docker exec airflow-webserver curl -s localhost:8080 > /dev/null 2>&1; do
  sleep $INTERVAL
  WAITED=$((WAITED + INTERVAL))
  echo "  ...still waiting for Airflow webserver ($WAITED sec elapsed)"
  if [ "$WAITED" -ge "$MAX_WAIT" ]; then
    echo "❌ Timed out waiting for Airflow webserver."
    exit 1
  fi
done

# Step 5: Create the Airflow admin user if it doesn't already exist
echo "👤 Checking if Airflow admin user exists..."
if ! docker exec airflow-webserver airflow users list | grep -q admin; then
  echo "👤 Creating Airflow admin user..."
  docker exec airflow-webserver airflow users create \
    --username admin \
    --password admin \
    --firstname Admin \
    --lastname User \
    --role Admin \
    --email admin@example.com
else
  echo "✅ Admin user already exists. Skipping creation."
fi

# Step 6: Output next steps
echo -e "\n✅ All systems go!"
echo -e "➡️  Access Airflow UI: http://localhost:8082"
echo -e "   Login with username: admin / password: admin"

echo -e "\n📌 REMINDER: Add the 'etl_postgres' connection manually in the Airflow UI:"
echo "  - Connection ID: etl_postgres"
echo "  - Type: Postgres"
echo "  - Host: etl-db"
echo "  - Port: 5432"
echo "  - Username: etluser"
echo "  - Password: etlpass"
echo "  - Schema: etl"
