#!/bin/bash

# Check if Namenode is formatted
if ! docker exec namenode hdfs namenode -metadataVersion >/dev/null 2>&1; then
    echo "Formatting Namenode..."
    docker exec namenode hdfs namenode -format -force
else
    echo "Namenode is already formatted."
fi

# Start HDFS Namenode and Datanode
echo "Starting HDFS Namenode and Datanode..."
docker compose -f docker-compose.app.yml up -d namenode datanode

# Wait for HDFS to be ready
echo "Waiting for HDFS to start..."
max_retries=30
retry_count=0
while ! docker exec namenode hdfs dfsadmin -report >/dev/null 2>&1; do
    echo "HDFS is not ready yet. Waiting..."
    sleep 10
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        echo "HDFS failed to start after $max_retries attempts. Exiting."
        docker compose -f docker-compose.app.yml logs namenode datanode
        exit 1
    fi
done
echo "HDFS is ready."
