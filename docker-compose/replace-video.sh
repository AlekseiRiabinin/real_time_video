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

# Function to shut down HDFS
shutdown_hdfs() {
    log "Shutting down HDFS..."
    docker compose -f "$DOCKER_COMPOSE_FILE" down
    log "HDFS has been shut down."
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

# Check if video.mp4 exists in the local directory
if [ ! -f ./video.mp4 ]; then
    log "Error: video.mp4 not found in the local directory. Please ensure the file exists."
    exit 1
fi

# Remove the old video file from HDFS if it exists
log "Checking if old video.mp4 exists in HDFS..."
if docker exec namenode hdfs dfs -test -e /videos/video.mp4; then
    log "Old video.mp4 found in HDFS. Removing it..."
    docker exec namenode hdfs dfs -rm -f /videos/video.mp4
    log "Old video.mp4 removed from HDFS."
else
    log "No old video.mp4 found in HDFS."
fi

# Copy video.mp4 to namenode container
log "Copying new video.mp4 to namenode container..."
if ! docker cp ./video.mp4 namenode:/tmp/video.mp4; then
    log "Error: Failed to copy video.mp4 to namenode container."
    exit 1
fi

# Create /videos directory in HDFS if it doesn't exist
log "Creating /videos directory in HDFS..."
docker exec -it namenode hdfs dfs -mkdir -p /videos

# Upload new video.mp4 to HDFS
log "Uploading new video.mp4 to HDFS..."
docker exec -it namenode hdfs dfs -put -f /tmp/video.mp4 /videos/video.mp4

# Verify the file is in HDFS
log "Verifying new video.mp4 in HDFS..."
docker exec -it namenode hdfs dfs -ls /videos

# Shut down HDFS
shutdown_hdfs

log "Video file replacement completed successfully."
