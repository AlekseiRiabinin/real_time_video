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

echo "Kafka app, Prometheus, and Grafana started."
