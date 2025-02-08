#!/bin/bash

# Function to check if NameNode is formatted
check_namenode_is_formatted() {
    if ! docker exec namenode ls /hadoop/dfs/name/current/VERSION >/dev/null 2>&1; then
        echo "NameNode is not formatted."
        docker compose -f docker-compose.app.yml down
        echo Run bash-script format-hdfs.sh first!
    else
        echo "NameNode is already formatted."
    fi
}

# Function to check for cluster ID mismatch
check_cluster_id_mismatch() {
    if docker compose -f docker-compose.app.yml logs datanode | grep -q "Incompatible clusterIDs"; then
        echo "Detected cluster ID mismatch. Reformatting NameNode and clearing DataNode..."
        docker compose -f docker-compose.app.yml down
        echo Run bash-script format-hdfs.sh first!
    fi
}

# Start all services
echo "Starting HDFS services..."
docker compose -f docker-compose.app.yml up -d namenode datanode

# Check if NameNode is formatted
check_namenode_is_formatted

# Check for cluster ID mismatch
check_cluster_id_mismatch

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
