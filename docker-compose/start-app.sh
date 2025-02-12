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

# Function to check if a file exists in the Spark container
check_spark_file() {
    local container_name=$1
    local file_path=$2
    if docker exec "$container_name" test -f "$file_path"; then
        echo "File $file_path exists in the Spark container."
        return 0
    else
        echo "File $file_path does not exist in the Spark container."
        return 1
    fi
}

# Spark container name and paths
SPARK_CONTAINER="spark-job"
SPARK_CONF_DIR="/opt/spark/conf"
CORE_SITE_PATH="$SPARK_CONF_DIR/core-site.xml"
HDFS_SITE_PATH="$SPARK_CONF_DIR/hdfs-site.xml"

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

# Check if video.mp4 is already in HDFS
echo "Checking if video.mp4 is already in HDFS..."
if docker exec namenode hdfs dfs -test -e /videos/video.mp4; then
    echo "video.mp4 is already in HDFS. Skipping copy."
else
    # Check if video.mp4 exists in the local directory
    if [ ! -f ./video.mp4 ]; then
        echo "Error: video.mp4 not found in the local directory. Please ensure the file exists."
        exit 1
    fi

    # Copy video.mp4 to namenode container
    echo "Copying video.mp4 to namenode container..."
    if ! docker cp ./video.mp4 namenode:/tmp/video.mp4; then
        echo "Error: Failed to copy video.mp4 to namenode container."
        exit 1
    fi

    # Create /videos directory in HDFS
    echo "Creating /videos directory in HDFS..."
    docker exec -it namenode hdfs dfs -mkdir -p /videos

    # Upload video.mp4 to HDFS
    echo "Uploading video.mp4 to HDFS..."
    docker exec -it namenode hdfs dfs -put /tmp/video.mp4 /videos/video.mp4

    # Verify the file is in HDFS
    echo "Verifying video.mp4 in HDFS..."
    docker exec -it namenode hdfs dfs -ls /videos
fi

# Start Kafka-brokers
docker compose -f docker-compose.app.yml up -d kafka-1 kafka-2

# Wait for Kafka-brokers to be ready
echo "Waiting for Kafka-brokers to start..."
sleep 10

# Check if Kafka brokers are up
while ! docker exec -it kafka-1 kafka-topics.sh --list --bootstrap-server kafka-1:9092; do
    echo "Waiting for kafka-1 to be ready..."
    sleep 5
done

while ! docker exec -it kafka-2 kafka-topics.sh --list --bootstrap-server kafka-2:9095; do
    echo "Waiting for kafka-2 to be ready..."
    sleep 5
done

# Create Kafka topics (number of partitions corresponds to number of Kafka consumers)
# kafka-service - 2 partitions, flink-job - 2 partitions
echo "Creating Kafka topics..."
docker exec -it kafka-1 kafka-topics.sh --create --topic __consumer_offsets --partitions 20 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
docker exec -it kafka-1 kafka-topics.sh --create --topic video-stream --partitions 4 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095
# docker exec -it kafka-1 kafka-topics.sh --create --topic anomaly-results --partitions 4 --replication-factor 2 --bootstrap-server kafka-1:9092,kafka-2:9095

# Start Kafka-service
echo "Waiting for Kafka-service to start..."
docker compose -f docker-compose.app.yml up -d kafka-service

# Wait for Kafka service to be ready
echo "Waiting for Kafka service to start..."
sleep 10

# Start Prometheus and Grafana
echo "Starting Prometheus and Grafana..."
docker compose -f docker-compose.app.yml up -d prometheus grafana

# Start Flink JobManager and TaskManager
echo "Starting Flink JobManager and TaskManager..."
if ! docker compose -f docker-compose.app.yml up -d jobmanager taskmanager; then
    echo "Error starting Flink services. Check the logs for more information."
    docker compose -f docker-compose.app.yml logs jobmanager taskmanager
    exit 1
fi

# Wait for JobManager to be ready
echo "Waiting for JobManager to be ready..."
max_retries=30
retry_count=0
while ! docker exec jobmanager curl -s http://jobmanager:8081 | grep -q "Flink Web Dashboard"; do
    echo "JobManager is not ready yet. Waiting..."
    sleep 5
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        echo "JobManager failed to start after $max_retries attempts. Exiting."
        docker compose -f docker-compose.app.yml logs jobmanager
        exit 1
    fi
done
echo "JobManager is ready."

# Start Flink job
echo "Starting Flink job..."
docker compose -f docker-compose.app.yml up -d flink-job

# Wait for Flink job to start
echo "Waiting for Flink job to start..."
sleep 60

# Check if Flink job is running
echo "Checking if Flink job is running..."
if docker exec jobmanager flink list | grep -q "FlinkJob Kafka Consumer"; then
    echo "Flink job 'FlinkJob Kafka Consumer' is running."
else
    echo "Error: Flink job 'FlinkJob Kafka Consumer' is not running. Check the logs for more information."
    docker compose -f docker-compose.app.yml logs flink-job
    exit 1
fi

# # Check if files already exist in the Spark container
# if check_spark_file "$SPARK_CONTAINER" "$CORE_SITE_PATH" && check_spark_file "$SPARK_CONTAINER" "$HDFS_SITE_PATH"; then
#     echo "HDFS configuration files are already present in the Spark container."
# else
#     echo "Copying HDFS configuration files to Spark container..."

#     # Copy core-site.xml
#     if ! docker cp namenode:/usr/local/hadoop/etc/hadoop/core-site.xml ./core-site.xml; then
#         echo "Error: Failed to copy core-site.xml from namenode."
#         exit 1
#     else
#         echo "Successfully copied core-site.xml from namenode."
#     fi

#     # Copy hdfs-site.xml
#     if ! docker cp namenode:/usr/local/hadoop/etc/hadoop/hdfs-site.xml ./hdfs-site.xml; then
#         echo "Error: Failed to copy hdfs-site.xml from namenode."
#         exit 1
#     else
#         echo "Successfully copied hdfs-site.xml from namenode."
#     fi

#     # Verify files on the host
#     if [ -f ./core-site.xml ] && [ -f ./hdfs-site.xml ]; then
#         echo "HDFS configuration files successfully copied to the host."
#     else
#         echo "Error: HDFS configuration files were not copied to the host."
#         exit 1
#     fi

#     # Copy files to Spark container
#     if ! docker cp ./core-site.xml spark-job:/opt/spark/conf/core-site.xml; then
#         echo "Error: Failed to copy core-site.xml to spark-job."
#         exit 1
#     else
#         echo "Successfully copied core-site.xml to spark-job."
#     fi

#     if ! docker cp ./hdfs-site.xml spark-job:/opt/spark/conf/hdfs-site.xml; then
#         echo "Error: Failed to copy hdfs-site.xml to spark-job."
#         exit 1
#     else
#         echo "Successfully copied hdfs-site.xml to spark-job."
#     fi

#     # Clean up
#     rm ./core-site.xml ./hdfs-site.xml
#     echo "Temporary files removed."
# fi

# Start Spark Master and Worker
echo "Starting Spark Master and Worker..."
docker compose -f docker-compose.app.yml up -d spark-master spark-worker
echo "Spark Master and Worker are ready."

# # Start Spark job
# echo "Starting Spark job..."
# docker compose -f docker-compose.kafka-app.yml up -d spark-job

echo "All services started successfully."
