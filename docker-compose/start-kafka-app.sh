#!/bin/bash

# Start Kafka-brokers
docker compose -f docker-compose.kafka-app.yml up -d kafka-1 kafka-2

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
docker compose -f docker-compose.kafka-app.yml up -d kafka-service

# Wait for Kafka service to be ready
echo "Waiting for Kafka service to start..."
sleep 10

# Start Prometheus and Grafana
echo "Starting Prometheus and Grafana..."
docker compose -f docker-compose.kafka-app.yml up -d prometheus grafana

# Start Flink JobManager and TaskManager
echo "Starting Flink JobManager and TaskManager..."
if ! docker compose -f docker-compose.kafka-app.yml up -d jobmanager taskmanager; then
    echo "Error starting Flink services. Check the logs for more information."
    docker compose -f docker-compose.kafka-app.yml logs jobmanager taskmanager
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
        docker compose -f docker-compose.kafka-app.yml logs jobmanager
        exit 1
    fi
done
echo "JobManager is ready."

# Start Flink job
echo "Starting Flink job..."
docker compose -f docker-compose.kafka-app.yml up -d flink-job

# Wait for Flink job to start
echo "Waiting for Flink job to start..."
sleep 10

# Check if Flink job is running
if docker exec jobmanager flink list | grep -q "FlinkJob Kafka Consumer"; then
    echo "Flink job 'FlinkJob Kafka Consumer' is running."
else
    echo "Error: Flink job 'FlinkJob Kafka Consumer' is not running. Check the logs for more information."
    docker compose -f docker-compose.kafka-app.yml logs flink-job
fi

echo "Kafka-app, Flink-job, Prometheus, and Grafana started."
