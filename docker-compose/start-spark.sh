#!/bin/bash

# Start Spark Master and Worker
echo "Starting Spark Master and Worker..."
docker compose -f docker-compose.app.yml up -d spark-master spark-worker
echo "Spark Master and Worker are ready."

# Wait for Spark Master to be ready
echo "Waiting for Spark Master to start..."
max_retries=10
retry_count=0
while ! docker compose -f docker-compose.app.yml logs spark-master | grep -q "Bound MasterWebUI to 0.0.0.0"; do
    echo "Spark Master is not ready yet. Waiting..."
    sleep 5
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        echo "Spark Master failed to start after $max_retries attempts. Exiting."
        docker compose -f docker-compose.app.yml logs spark-master
        exit 1
    fi
done
echo "Spark Master is ready."

# Start Spark job
echo "Starting Spark job..."
docker compose -f docker-compose.app.yml up -d spark-job

# Wait for Spark job to start
echo "Waiting for Spark job to start..."
sleep 20

# Check if Spark job is running
echo "Checking if Spark job is running..."
if docker compose -f docker-compose.app.yml logs spark-job | grep -q "ApplicationStateChanged"; then
    echo "Spark job is running."
else
    echo "Error: Spark job failed to start. Check the logs for more information."
    docker compose -f docker-compose.app.yml logs spark-job
    exit 1
fi
