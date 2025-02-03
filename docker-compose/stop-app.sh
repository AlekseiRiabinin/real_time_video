#!/bin/bash

# Stop Kafka-app
docker compose -f docker-compose.app.yml down

# Wait for Kafka-app to be ready
echo "Waiting for All services to stop..."
sleep 10

echo "All services stoped successfully."