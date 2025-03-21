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

# Function to start a specific producer
start_producer() {
    local producer_type=$1
    case $producer_type in
        kafka)
            log "Starting Kafka Client..."
            check_port_availability 9080  # Check if port 9080 is available
            docker compose -f "$DOCKER_COMPOSE_FILE" up -d "$producer_type-client"
            ;;
        akka)
            log "Starting Akka Client..."
            check_port_availability 9081  # Check if port 9081 is available
            docker compose -f "$DOCKER_COMPOSE_FILE" up -d "$producer_type-client"
            ;;
        cats)
            log "Starting Cats Client..."
            check_port_availability 9082  # Check if port 9082 is available
            docker compose -f "$DOCKER_COMPOSE_FILE" up -d "$producer_type-client"
            ;;
        fs2)
            log "Starting FS2 Client..."
            check_port_availability 9083  # Check if port 9083 is available
            docker compose -f "$DOCKER_COMPOSE_FILE" up -d "$producer_type-client"
            ;;
        zio)
            log "Starting ZIO Client..."
            check_port_availability 9084  # Check if port 9084 is available
            docker compose -f "$DOCKER_COMPOSE_FILE" up -d "$producer_type-client"
            ;;
        *)
            log "Invalid producer type. Use 'kafka', 'akka', 'cats', 'fs2', or 'zio'."
            exit 1
            ;;
    esac

    sleep 10
    if docker ps | grep -q "$producer_type-client"; then
        log "$producer_type Client is running."
    else
        log "Error: $producer_type Client is not running. Check the logs for more information."
        docker compose -f "$DOCKER_COMPOSE_FILE" logs "$producer_type-client"
        exit 1
    fi
}

# Function to wait for Kafka brokers to be ready
wait_for_kafka_brokers() {
    local max_retries=30
    local retry_count=0

    log "Waiting for Kafka brokers to be ready..."
    while ! docker exec -it kafka-1 kafka-topics.sh --list --bootstrap-server kafka-1:9092 >/dev/null 2>&1; do
        log "Kafka brokers are not ready yet. Waiting..."
        sleep 10
        retry_count=$((retry_count + 1))
        if [ $retry_count -ge $max_retries ]; then
            log "Error: Kafka brokers failed to start after $max_retries attempts. Exiting."
            docker compose -f "$DOCKER_COMPOSE_FILE" logs kafka-1 kafka-2
            exit 1
        fi
    done
    log "Kafka brokers are ready."
}

# Function to check if port is available
check_port_availability() {
    local port=$1
    if netstat -tuln | grep -q ":$port "; then
        log "Port $port is already in use."
        exit 1
    else
        log "Port $port is available."
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

# Function to start Kafka brokers
start_kafka_brokers() {
    log "Starting Kafka brokers..."
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d kafka-1 kafka-2

    # Wait for Kafka brokers to be ready
    wait_for_kafka_brokers

    # Check if Kafka brokers are up
    log "Waiting for Kafka brokers to start..."
    sleep 10

    while ! docker exec -it kafka-1 kafka-topics.sh --list --bootstrap-server kafka-1:9092; do
        log "Waiting for kafka-1 to be ready..."
        sleep 5
    done

    while ! docker exec -it kafka-2 kafka-topics.sh --list --bootstrap-server kafka-2:9095; do
        log "Waiting for kafka-2 to be ready..."
        sleep 5
    done

    # Check if topics exist before creating them
    if ! docker exec -it kafka-1 kafka-topics.sh --describe --topic __consumer_offsets --bootstrap-server kafka-1:9092 >/dev/null 2>&1; then
        log "Creating Kafka topic __consumer_offsets..."
        docker exec -it kafka-1 kafka-topics.sh --create --topic __consumer_offsets --partitions 20 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
    fi

    if ! docker exec -it kafka-1 kafka-topics.sh --describe --topic video-stream --bootstrap-server kafka-1:9092 >/dev/null 2>&1; then
        log "Creating Kafka topic video-stream..."
        docker exec -it kafka-1 kafka-topics.sh --create --topic video-stream --partitions 4 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
    fi

    if ! docker exec -it kafka-1 kafka-topics.sh --describe --topic processed-data --bootstrap-server kafka-1:9092 >/dev/null 2>&1; then
        log "Creating Kafka topic processed-data..."
        docker exec -it kafka-1 kafka-topics.sh --create --topic processed-data --partitions 4 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
    fi
}

# Function to start Kafka service
start_kafka_service() {
    log "Starting Kafka service..."
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d kafka-service

    # Wait for Kafka service to be ready
    log "Waiting for Kafka service to start..."
    sleep 10
}

# Function to start Prometheus and Grafana
start_monitoring_services() {
    log "Starting Prometheus and Grafana..."
    docker compose -f "$DOCKER_COMPOSE_FILE" up -d prometheus grafana
}

# Main function to orchestrate the script
main() {
    if [ $# -ne 1 ]; then
        log "Usage: $0 <producer-type>"
        log "  <producer-type>: kafka | akka | cats | fs2 | zio"
        exit 1
    fi

    # Paths and variables
    DOCKER_COMPOSE_FILE="/home/aleksei/Projects/real_time_video/docker-compose/docker-compose.app.yml"
    LOCAL_MODEL_PATH="/home/aleksei/Projects/real_time_video/apps/spark-ml/models/saved_model"
    HDFS_MODEL_PATH="/models/saved_model"
    PRODUCER_TYPE=$1
    NAMENODE_CONTAINER="namenode"

    # Check if Docker Compose file exists
    if [ ! -f "$DOCKER_COMPOSE_FILE" ]; then
        log "Error: Docker Compose file not found at $DOCKER_COMPOSE_FILE."
        exit 1
    fi

    # Start HDFS services
    start_hdfs_services

    # Start Kafka brokers
    start_kafka_brokers

    # Start Kafka service
    start_kafka_service

    # Start Prometheus and Grafana
    start_monitoring_services

    # Start the selected producer
    start_producer $PRODUCER_TYPE

    log "All services started successfully."
}

# Execute the main function
main "$@"
