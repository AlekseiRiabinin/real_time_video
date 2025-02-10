#!/bin/bash

# Start Spark Master and Worker
echo "Starting Spark Master and Worker..."
docker compose -f docker-compose.app.yml up -d spark-master spark-worker

echo "Waiting for Spark Master to be ready..."
docker compose -f docker-compose.app.yml logs spark-master

echo "Waiting for Spark Worker to register..."
docker compose -f docker-compose.app.yml logs spark-worker

# echo "Waiting for Spark Master to be ready..."
# max_retries=30
# retry_count=0
# while ! docker exec spark-master bash -c "curl -s http://localhost:8080 > /dev/null 2>&1"; do
#     echo "Spark Master is not ready yet. Waiting..."
#     sleep 5
#     retry_count=$((retry_count + 1))
#     if [ $retry_count -ge $max_retries ]; then
#         echo "Spark Master failed to start after $max_retries attempts. Exiting."
#         docker compose -f docker-compose.app.yml logs spark-master
#         exit 1
#     fi
# done

# echo "Spark Master is responding. Checking for worker..."

# # Wait for worker to register
# max_retries=30
# retry_count=0
# while ! docker exec spark-master bash -c "curl -s http://localhost:8080 | grep -q 'Workers (1)'"; do
#     echo "Waiting for Spark Worker to register..."
#     sleep 5
#     retry_count=$((retry_count + 1))
#     if [ $retry_count -ge $max_retries ]; then
#         echo "Spark Worker failed to register after $max_retries attempts. Exiting."
#         docker compose -f docker-compose.app.yml logs spark-worker
#         exit 1
#     fi
# done

echo "Spark Master and Worker are ready."