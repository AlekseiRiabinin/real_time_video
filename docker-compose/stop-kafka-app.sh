#!/bin/bash

# Stop Kafka-app
docker compose -f docker-compose.kafka-app.yml down

# Wait for Kafka-app to be ready
echo "Waiting for Kafka-app to stop..."
sleep 10

echo "Kafka-app stopped."