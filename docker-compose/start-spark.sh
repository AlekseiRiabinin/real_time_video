#!/bin/bash

# Function to log messages
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1"
}

# Function to check if NameNode is formatted
check_namenode_is_formatted() {
    if ! docker exec namenode ls /hadoop/dfs/name/current/VERSION >/dev/null 2>&1; then
        log "NameNode is not formatted."
        docker compose -f "$DOCKER_COMPOSE_FILE" down
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
        docker compose -f "$DOCKER_COMPOSE_FILE" down
        log "Run bash-script format-hdfs.sh first!"
        exit 1
    fi
}

# Function to check and upload video files to HDFS
upload_videos_to_hdfs() {
    log "Checking if video files exist in HDFS..."
    for i in {1..10}; do
        video_file="video_$(printf "%02d" $i).mp4"
        if ! docker exec namenode hdfs dfs -test -e /videos/$video_file; then
            log "$video_file not found in HDFS. Uploading..."

            # Check if the file exists locally
            if [ ! -f "./videos/$video_file" ]; then
                log "Warning: $video_file not found in the local directory. Skipping upload."
                continue
            fi

            # Copy the file to the namenode container
            log "Copying $video_file to namenode container..."
            docker cp "./videos/$video_file" namenode:/tmp/$video_file

            # Upload the file to HDFS
            log "Uploading $video_file to HDFS..."
            docker exec -it namenode hdfs dfs -put -f /tmp/$video_file /videos/$video_file
        else
            log "$video_file already exists in HDFS. Skipping upload."
        fi
    done
}

# Function to check if HDFS is ready
check_hdfs_ready() {
    log "Waiting for HDFS to start..."
    max_retries=30
    retry_count=0
    sleep 10
    while ! docker exec namenode hdfs dfsadmin -report >/dev/null 2>&1; do
        log "HDFS is not ready yet. Waiting..."
        sleep 5
        retry_count=$((retry_count + 1))
        if [ $retry_count -ge $max_retries ]; then
            log "HDFS failed to start after $max_retries attempts. Exiting."
            docker compose -f "$DOCKER_COMPOSE_FILE" logs namenode datanode
            exit 1
        fi
    done
    log "HDFS is ready."

    # Disable safe mode if it is enabled
    log "Checking if HDFS is in safe mode..."
    if docker exec namenode hdfs dfsadmin -safemode get | grep -q "ON"; then
        log "HDFS is in safe mode. Disabling safe mode..."
        docker exec -it namenode hdfs dfsadmin -safemode leave
        log "Safe mode is now OFF."
    else
        log "HDFS is not in safe mode."
    fi
}

# Function to check if Spark Master is ready
check_spark_master_ready() {
    log "Waiting for Spark Master to start..."
    max_retries=10
    retry_count=0
    while ! docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-master | grep -q "Bound MasterWebUI to 0.0.0.0"; do
        log "Spark Master is not ready yet. Waiting..."
        sleep 5
        retry_count=$((retry_count + 1))
        if [ $retry_count -ge $max_retries ]; then
            log "Spark Master failed to start after $max_retries attempts. Exiting."
            docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-master
            exit 1
        fi
    done
    log "Spark Master is ready."
}

# Function to check if Spark Worker is ready
check_spark_worker_ready() {
    log "Waiting for Spark Worker to start..."
    max_retries=10
    retry_count=0
    while ! docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-worker | grep -q "Successfully registered with master"; do
        log "Spark Worker is not ready yet. Waiting..."
        sleep 5
        retry_count=$((retry_count + 1))
        if [ $retry_count -ge $max_retries ]; then
            log "Spark Worker failed to start after $max_retries attempts. Exiting."
            docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-worker
            exit 1
        fi
    done
    log "Spark Worker is ready."
}

# Function to check if Spark job is running
check_spark_job_ready() {
    log "Waiting for Spark job to start..."
    max_retries=20
    retry_count=0
    while ! docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-job | grep -q "Spark session initialized successfully"; do
        log "Spark job is not ready yet. Waiting..."
        sleep 5
        retry_count=$((retry_count + 1))
        if [ $retry_count -ge $max_retries ]; then
            log "Spark job failed to start after $max_retries attempts. Exiting."
            docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-job
            exit 1
        fi
    done
    log "Spark job is running."
}

# Function to start HDFS services
start_hdfs_services() {
    log "Starting HDFS services..."
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d namenode datanode

    # Check NameNode and cluster ID
    check_namenode_is_formatted
    check_cluster_id_mismatch

    # Wait for HDFS to be ready
    check_hdfs_ready

    log "Creating /videos directory in HDFS..."
    if ! docker exec -it namenode hdfs dfs -test -d /videos; then
        docker exec -it namenode hdfs dfs -mkdir -p /videos
    fi

    log "Creating /videos/checkpoint directory in HDFS..."
    if ! docker exec -it namenode hdfs dfs -test -d /videos/checkpoint; then
        docker exec -it namenode hdfs dfs -mkdir -p /videos/checkpoint
        docker exec -it namenode hdfs dfs -chmod -R 777 /videos/checkpoint
    fi

    # Upload video files to HDFS if they don't exist
    upload_videos_to_hdfs

    # Verify the files are in HDFS
    log "Verifying video files in HDFS..."
    docker exec -it namenode hdfs dfs -ls /videos

    # Copy the model to HDFS
    log "Checking if the model is already in HDFS..."
    if docker exec -it $NAMENODE_CONTAINER hdfs dfs -test -e $HDFS_MODEL_PATH; then
        log "Model is already in HDFS. Skipping copy."
    else
        log "Model not found in HDFS. Copying the model to HDFS..."

        # Copy the model to the namenode container's local filesystem
        log "Copying the model to the namenode container..."
        if ! docker cp "$LOCAL_MODEL_PATH" namenode:/tmp/saved_model; then
            log "Error: Failed to copy the model to the namenode container."
            exit 1
        else
            log "Model copied to the namenode container successfully."
        fi

        # Upload the model from the namenode container to HDFS
        log "Uploading the model to HDFS..."
        if docker exec -it namenode hdfs dfs -put /tmp/saved_model /models/saved_model; then
            log "Model uploaded to HDFS successfully."
        else
            log "Error: Failed to upload the model to HDFS."
            exit 1
        fi

        # Clean up the temporary files in the namenode container
        log "Cleaning up temporary files in the namenode container..."
        docker exec -it --user root namenode rm -rf /tmp/saved_model
    fi
}

# Function to start Spark services
start_spark_services() {
    log "Starting Spark Master and Worker..."
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d spark-master spark-worker

    # Wait for Spark Master and Worker to be ready
    check_spark_master_ready
    check_spark_worker_ready

    # Start Spark job
    log "Starting Spark job..."
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d spark-job

    # Wait for Spark job to start
    check_spark_job_ready
}

# Load environment variables
load_environment_variables() {
    if [ -f .env ]; then
        export $(cat .env | xargs)
    fi
}

# Main function to orchestrate the script
main() {
    # Load environment variables

    # Paths and variables
    DOCKER_COMPOSE_FILE="/home/aleksei/Projects/real_time_video/docker-compose/docker-compose.app.yml"
    LOCAL_MODEL_PATH="/home/aleksei/Projects/real_time_video/apps/spark-ml/models/saved_model"
    HDFS_MODEL_PATH="/models/saved_model"
    NAMENODE_CONTAINER="namenode"

    # Start HDFS services
    start_hdfs_services

    # Start Spark services
    start_spark_services

    log "All services started successfully."
}

# Execute the main function
main
