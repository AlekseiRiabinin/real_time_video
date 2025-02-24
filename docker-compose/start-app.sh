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

# Function to check if a file exists in the Spark container
check_spark_file() {
    local container_name=$1
    local file_path=$2
    if ! docker ps | grep -q "$container_name"; then
        log "Error: Container $container_name is not running."
        return 1
    fi
    if docker exec "$container_name" test -f "$file_path"; then
        log "File $file_path exists in the Spark container."
        return 0
    else
        log "File $file_path does not exist in the Spark container."
        return 1
    fi
}

# Function to start a specific producer
start_producer() {
    local producer_type=$1
    case $producer_type in
        kafka|akka|cats|fs2|zio)
            log "Starting $producer_type Client..."
            docker compose -f "$DOCKER_COMPOSE_FILE" up -d "$producer_type-client"
            sleep 10
            if docker ps | grep -q "$producer_type-client"; then
                log "$producer_type Client is running."
            else
                log "Error: $producer_type Client is not running. Check the logs for more information."
                docker compose -f "$DOCKER_COMPOSE_FILE" logs "$producer_type-client"
                exit 1
            fi
            ;;
        *)
            log "Invalid producer type. Use 'kafka', 'akka', 'cats', 'fs2', or 'zio'."
            exit 1
            ;;
    esac
}

# Main script
if [ $# -ne 1 ]; then
    log "Usage: $0 <producer-type>"
    log "  <producer-type>: kafka | akka | cats | fs2 | zio"
    exit 1
fi

# Spark container name and paths
SPARK_CONTAINER="spark-job"
SPARK_CONF_DIR="/opt/spark/conf"
CORE_SITE_PATH="$SPARK_CONF_DIR/core-site.xml"
HDFS_SITE_PATH="$SPARK_CONF_DIR/hdfs-site.xml"

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

# Handling missing files
if [ ! -f "/home/aleksei/Projects/real_time_video/apps/spark-app/target/scala-2.12/spark-job-fat.jar" ]; then
    log "Error: Fat JAR not found. Build the Spark project first."
    exit 1
fi

if [ ! -d "/home/aleksei/Projects/real_time_video/apps/spark-ml/models/saved_model" ]; then
    log "Error: Model directory not found. Ensure the model is available."
    exit 1
fi

# +++++++++++++++++++++++++++++++++++++++++++++++ #
# 1. Start HDFS services (namenode and datanode). #
# +++++++++++++++++++++++++++++++++++++++++++++++ #

# Start HDFS services
log "Starting HDFS services..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d namenode datanode

# Check NameNode and cluster ID
check_namenode_is_formatted
check_cluster_id_mismatch

# Wait for HDFS to be ready
log "Waiting for HDFS to start..."
max_retries=10
retry_count=0
while ! docker exec namenode hdfs dfsadmin -report >/dev/null 2>&1; do
    log "HDFS is not ready yet. Waiting..."
    sleep 10
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        log "HDFS failed to start after $max_retries attempts. Exiting."
        docker compose -f "$DOCKER_COMPOSE_FILE" logs namenode datanode
        exit 1
    fi
done
log "HDFS is ready."

# Check if video.mp4 is already in HDFS
log "Checking if video.mp4 is already in HDFS..."
if docker exec namenode hdfs dfs -test -e /videos/video.mp4; then
    log "video.mp4 is already in HDFS. Skipping copy."
else
    # Check if video.mp4 exists in the local directory
    if [ ! -f ./video.mp4 ]; then
        log "Error: video.mp4 not found in the local directory. Please ensure the file exists."
        exit 1
    fi

    # Copy video.mp4 to namenode container
    log "Copying video.mp4 to namenode container..."
    if ! docker cp ./video.mp4 namenode:/tmp/video.mp4; then
        log "Error: Failed to copy video.mp4 to namenode container."
        exit 1
    fi

    # Create /videos directory in HDFS
    log "Creating /videos directory in HDFS..."
    docker exec -it namenode hdfs dfs -mkdir -p /videos

    # Upload video.mp4 to HDFS
    log "Uploading video.mp4 to HDFS..."
    docker exec -it namenode hdfs dfs -put /tmp/video.mp4 /videos/video.mp4

    # Verify the file is in HDFS
    log "Verifying video.mp4 in HDFS..."
    docker exec -it namenode hdfs dfs -ls /videos
fi

# Copy the model to HDFS
log "Checking if the model is already in HDFS..."
if docker exec -it $NAMENODE_CONTAINER hdfs dfs -test -e $HDFS_MODEL_PATH; then
    log "Model is already in HDFS. Skipping copy."
else
    log "Model not found in HDFS. Copying the model to HDFS..."

    # Copy the model to the namenode container's local filesystem
    log "Copying the model to the namenode container..."
    if ! docker cp /home/aleksei/Projects/real_time_video/apps/spark-ml/models/saved_model namenode:/tmp/saved_model; then
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

# +++++++++++++++++++++++++++++++++++++++++++++ #
# 2. Start Kafka brokers (kafka-1 and kafka-2). #
# +++++++++++++++++++++++++++++++++++++++++++++ #

# Start Kafka-brokers
docker compose -f "$DOCKER_COMPOSE_FILE" up -d kafka-1 kafka-2

# Wait for Kafka-brokers to be ready
log "Waiting for Kafka-brokers to start..."
sleep 10

# Check if Kafka brokers are up
while ! docker exec -it kafka-1 kafka-topics.sh --list --bootstrap-server kafka-1:9092; do
    log "Waiting for kafka-1 to be ready..."
    sleep 5
done

while ! docker exec -it kafka-2 kafka-topics.sh --list --bootstrap-server kafka-2:9095; do
    log "Waiting for kafka-2 to be ready..."
    sleep 5
done

# Create Kafka topics (number of partitions corresponds to number of Kafka consumers)
# kafka-service - 2 partitions, flink-job - 2 partitions
# echo "Creating Kafka topics..."
# docker exec -it kafka-1 kafka-topics.sh --create --topic __consumer_offsets --partitions 20 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
# docker exec -it kafka-1 kafka-topics.sh --create --topic video-stream --partitions 4 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
# docker exec -it kafka-1 kafka-topics.sh --create --topic anomaly-results --partitions 4 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
# Check if topics exist before creating them
if ! docker exec -it kafka-1 kafka-topics.sh --describe --topic __consumer_offsets --bootstrap-server kafka-1:9092 >/dev/null 2>&1; then
    log "Creating Kafka topic __consumer_offsets..."
    docker exec -it kafka-1 kafka-topics.sh --create --topic __consumer_offsets --partitions 20 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
fi

if ! docker exec -it kafka-1 kafka-topics.sh --describe --topic video-stream --bootstrap-server kafka-1:9092 >/dev/null 2>&1; then
    log "Creating Kafka topic video-stream..."
    docker exec -it kafka-1 kafka-topics.sh --create --topic video-stream --partitions 4 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
fi

# +++++++++++++++++++++++++++++++++++++++ #
# 3. Start Kafka service (kafka-service). #
# +++++++++++++++++++++++++++++++++++++++ #

# Start Kafka-service
log "Waiting for Kafka-service to start..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d kafka-service

# Wait for Kafka service to be ready
log "Waiting for Kafka service to start..."
sleep 10

# ++++++++++++++++++++++++++++++++ #
# 4. Start Prometheus and Grafana. #
# ++++++++++++++++++++++++++++++++ #

# Start Prometheus and Grafana
log "Starting Prometheus and Grafana..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d prometheus grafana

# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #
# 5. Start Flink (jobmanager and taskmanager) and the Flink job (flink-job). #
# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #

# Start Flink JobManager and TaskManager
log "Starting Flink JobManager and TaskManager..."
if ! docker compose -f "$DOCKER_COMPOSE_FILE" up -d jobmanager taskmanager; then
    log "Error starting Flink services. Check the logs for more information."
    docker compose -f "$DOCKER_COMPOSE_FILE" logs jobmanager taskmanager
    exit 1
fi

# Wait for JobManager to be ready
log "Waiting for JobManager to be ready..."
max_retries=30
retry_count=0
while ! docker exec jobmanager curl -s http://jobmanager:8081 | grep -q "Flink Web Dashboard"; do
    log "JobManager is not ready yet. Waiting..."
    sleep 5
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        log "JobManager failed to start after $max_retries attempts. Exiting."
        docker compose -f "$DOCKER_COMPOSE_FILE" logs jobmanager
        exit 1
    fi
done
log "JobManager is ready."

# Start Flink job
log "Starting Flink job..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d flink-job

# Wait for Flink job to start
log "Waiting for Flink job to start..."
sleep 60

# Check if Flink job is running
log "Checking if Flink job is running..."
if docker exec jobmanager flink list | grep -q "FlinkJob Kafka Consumer"; then
    log "Flink job 'FlinkJob Kafka Consumer' is running."
else
    log "Error: Flink job 'FlinkJob Kafka Consumer' is not running. Check the logs for more information."
    docker compose -f "$DOCKER_COMPOSE_FILE" logs flink-job
    exit 1
fi

# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #
# 6. Copy HDFS configuration files into the spark-job container. #
# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #

# Copy HDFS configuration files to the host
log "Copying HDFS configuration files to the host..."
docker cp namenode:/usr/local/hadoop/etc/hadoop/core-site.xml ./core-site.xml
docker cp namenode:/usr/local/hadoop/etc/hadoop/hdfs-site.xml ./hdfs-site.xml

# Verify files on the host
if [ -f ./core-site.xml ] && [ -f ./hdfs-site.xml ]; then
    log "HDFS configuration files successfully copied to the host."
else
    log "Error: HDFS configuration files were not copied to the host."
    exit 1
fi

# Copy HDFS configuration files to Spark container
if docker cp ./core-site.xml spark-job:/opt/spark/conf/core-site.xml; then
    log "core-site.xml is copied."
else
    log "Error: core-site.xml was not copied to Spark container."
    exit 1
fi
if docker cp ./hdfs-site.xml spark-job:/opt/spark/conf/hdfs-site.xml; then
    log "hdfs-site.xml is copied."
else
    log "Error: hdfs-site.xml was not copied to Spark container."
    exit 1
fi

# Clean up
rm ./core-site.xml ./hdfs-site.xml
log "Temporary files removed."

# Verify configuration files before starting the Spark Job
if ! docker exec spark-job test -f /opt/spark/conf/core-site.xml || ! docker exec spark-job test -f /opt/spark/conf/hdfs-site.xml; then
    log "Error: HDFS configuration files are missing in the Spark container."
    exit 1
fi

# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #
# 7. Start Spark services (spark-master, spark-worker, and spark-job). #
# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #

# Start Spark Master and Worker
log "Starting Spark Master and Worker..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d spark-master spark-worker
log "Spark Master and Worker are ready."

# Wait for Spark Master to be ready
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

# Start Spark job
log "Starting Spark job..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d spark-job

# Wait for Spark job to start
log "Waiting for Spark job to start..."
sleep 20

# Check if Spark job is running
log "Checking if Spark job is running..."
if docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-job | grep -q "ApplicationStateChanged"; then
    log "Spark job is running."
else
    log "Error: Spark job failed to start. Check the logs for more information."
    docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-job
    exit 1
fi

# +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #
# 8. Start the selected producer (kafka-client, akka-client, etc.). #
# +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #

# Start the selected producer
start_producer $PRODUCER_TYPE

log "All services started successfully."

# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #
# ./start-app.sh akka -> How to start bash-script with different producers #
# ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++ #
