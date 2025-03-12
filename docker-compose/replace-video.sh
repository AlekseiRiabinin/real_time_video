#!/bin/bash

# Function to log messages
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1"
}

# Function to check if NameNode is formatted
check_namenode_is_formatted() {
    if ! docker exec namenode ls /hadoop/dfs/name/current/VERSION >/dev/null 2>&1; then
        log "NameNode is not formatted."
        log "Run bash-script format-hdfs.sh first!"
        exit 1
    else
        log "NameNode is already formatted."
    fi
}

# Function to check for cluster ID mismatch
check_cluster_id_mismatch() {
    if docker compose -f "$DOCKER_COMPOSE_FILE" logs datanode | grep -q "Incompatible clusterIDs"; then
        log "Detected cluster ID mismatch. Reformatting NameNode and clearing DataNode..."
        log "Run bash-script format-hdfs.sh first!"
        exit 1
    fi
}

# Function to start HDFS if not running
start_hdfs_if_not_running() {
    if ! docker ps | grep -q "namenode"; then
        log "HDFS is not running. Starting HDFS..."
        docker compose -f "$DOCKER_COMPOSE_FILE" up -d namenode datanode
        log "Waiting for HDFS to start..."
        sleep 10
    else
        log "HDFS is already running."
    fi
}

# Function to wait for NameNode to exit safe mode
wait_for_safe_mode_exit() {
    local max_retries=30
    local retry_count=0

    log "Waiting for NameNode to exit safe mode..."
    while docker exec namenode hdfs dfsadmin -safemode get | grep -q "ON"; do
        log "NameNode is still in safe mode. Waiting..."
        sleep 5
        retry_count=$((retry_count + 1))
        if [ $retry_count -ge $max_retries ]; then
            log "Error: NameNode did not exit safe mode after $max_retries attempts. Exiting."
            exit 1
        fi
    done
    log "NameNode has exited safe mode."
}

# Function to shut down HDFS nodes
shutdown_hdfs_nodes() {
    log "Shutting down HDFS nodes..."
    docker compose -f "$DOCKER_COMPOSE_FILE" down namenode datanode
    if [ $? -eq 0 ]; then
        log "HDFS nodes (NameNode and DataNode) have been shut down successfully."
    else
        log "Error: Failed to shut down HDFS nodes."
        exit 1
    fi
}

# Load environment variables
if [ -f .env ]; then
  export $(cat .env | xargs)
fi

# Paths and variables
DOCKER_COMPOSE_FILE="/home/aleksei/Projects/real_time_video/docker-compose/docker-compose.app.yml"
NAMENODE_CONTAINER="namenode"

# Check if Docker Compose file exists
if [ ! -f "$DOCKER_COMPOSE_FILE" ]; then
    log "Error: Docker Compose file not found at $DOCKER_COMPOSE_FILE."
    exit 1
fi

# Start HDFS if not running
start_hdfs_if_not_running

# Check if NameNode is formatted and cluster ID is consistent
check_namenode_is_formatted
check_cluster_id_mismatch

# Wait for NameNode to exit safe mode
wait_for_safe_mode_exit

# Create /videos directory in HDFS if it doesn't exist
log "Creating /videos directory in HDFS..."
docker exec -it namenode hdfs dfs -mkdir -p /videos

# Remove all old video files from HDFS if they exist
log "Removing old video files from HDFS..."
docker exec namenode hdfs dfs -rm -f /videos/video_*.mp4

# Copy new video files to namenode container
log "Copying new video files to namenode container..."
for i in {1..10}; do
  video_file="video_$(printf "%02d" $i).mp4"
  if [ ! -f "./videos/$video_file" ]; then
    log "Error: $video_file not found in the local directory. Please ensure the file exists."
    exit 1
  fi
  docker cp "./videos/$video_file" namenode:/tmp/$video_file
done

# Upload new video files to HDFS
log "Uploading new video files to HDFS..."
for i in {1..10}; do
  video_file="video_$(printf "%02d" $i).mp4"
  docker exec -it namenode hdfs dfs -put -f /tmp/$video_file /videos/$video_file
done

# Verify the files are in HDFS
log "Verifying new video files in HDFS..."
docker exec -it namenode hdfs dfs -ls /videos

log "Video files replacement completed successfully."

# Shut down HDFS nodes after replacement
shutdown_hdfs_nodes
