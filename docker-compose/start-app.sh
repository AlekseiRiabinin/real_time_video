#!/bin/bash

# Check if Namenode is formatted
if ! docker run --rm -v namenode-data:/hadoop/dfs/name alexflames77/custom-hadoop-namenode:3.3.6-java17 hdfs namenode -metadataVersion >/dev/null 2>&1; then
    echo "Formatting Namenode..."
    docker run --rm -v namenode-data:/hadoop/dfs/name alexflames77/custom-hadoop-namenode:3.3.6-java17 hdfs namenode -format -force
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

# # Start Spark Master and Worker
# echo "Starting Spark Master and Worker..."
# docker compose -f docker-compose.kafka-app.yml up -d spark-master spark-worker

# # Start Spark job
# echo "Starting Spark job..."
# docker compose -f docker-compose.kafka-app.yml up -d spark-job

echo "All services started successfully."

# If this is the first time you are starting the Namenode, you need to format it. This initializes the metadata directory
# docker run --rm -v namenode-data:/hadoop/dfs/name alexflames77/custom-hadoop-namenode:3.3.6-java17 hdfs namenode -format