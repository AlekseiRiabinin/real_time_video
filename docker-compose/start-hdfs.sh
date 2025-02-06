#!/bin/bash

# Check if Namenode is formatted
if [ ! -d "namenode-data/current" ]; then
    echo "Formatting Namenode..."
    docker run --rm -v namenode-data:/hadoop/dfs/name alexflames77/custom-hadoop-namenode:3.3.6-java17 hdfs namenode -format
else
    echo "Namenode is already formatted."
fi

# Start HDFS Namenode and Datanode
echo "Starting HDFS Namenode and Datanode..."
docker compose -f docker-compose.app.yml up -d namenode datanode

# Wait for Namenode to be ready
echo "Waiting for Namenode to start..."
max_retries=30
retry_count=0
while ! docker exec namenode hdfs dfsadmin -report >/dev/null 2>&1; do
    echo "Namenode is not ready yet. Waiting..."
    sleep 10
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        echo "Namenode failed to start after $max_retries attempts. Exiting."
        docker compose -f docker-compose.app.yml logs namenode
        exit 1
    fi
done
echo "Namenode is ready."
