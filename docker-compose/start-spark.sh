#!/bin/bash

# Spark container name and paths
SPARK_CONTAINER="spark-job"
SPARK_CONF_DIR="/opt/bitnami/spark/conf"
CORE_SITE_PATH="$SPARK_CONF_DIR/core-site.xml"
HDFS_SITE_PATH="$SPARK_CONF_DIR/hdfs-site.xml"

# Paths and variables
DOCKER_COMPOSE_FILE="/home/aleksei/Projects/real_time_video/docker-compose/docker-compose.app.yml"
LOCAL_MODEL_PATH="/home/aleksei/Projects/real_time_video/apps/spark-ml/models/saved_model"
HDFS_MODEL_PATH="/models/saved_model"
PRODUCER_TYPE=$1
NAMENODE_CONTAINER="namenode"

# Start HDFS services
echo "Starting HDFS services..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d namenode datanode

# Wait for HDFS to be ready
echo "Waiting for HDFS to start..."
max_retries=10
retry_count=0
while ! docker exec namenode hdfs dfsadmin -report >/dev/null 2>&1; do
    echo "HDFS is not ready yet. Waiting..."
    sleep 10
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        echo "HDFS failed to start after $max_retries attempts. Exiting."
        docker compose -f "$DOCKER_COMPOSE_FILE" logs namenode datanode
        exit 1
    fi
done
echo "HDFS is ready."

# Copy HDFS configuration files to the host
echo "Copying HDFS configuration files to the host..."
docker cp namenode:/usr/local/hadoop/etc/hadoop/core-site.xml ./core-site.xml
docker cp namenode:/usr/local/hadoop/etc/hadoop/hdfs-site.xml ./hdfs-site.xml

# Verify files on the host
if [ -f ./core-site.xml ] && [ -f ./hdfs-site.xml ]; then
    echo "HDFS configuration files successfully copied to the host."
else
    echo "Error: HDFS configuration files were not copied to the host."
    exit 1
fi

# Start Spark Master and Worker
echo "Starting Spark Master and Worker..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d spark-master spark-worker
echo "Spark Master and Worker are ready."

# Wait for Spark Master to be ready
echo "Waiting for Spark Master to start..."
max_retries=10
retry_count=0
while ! docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-master | grep -q "Bound MasterWebUI to 0.0.0.0"; do
    echo "Spark Master is not ready yet. Waiting..."
    sleep 5
    retry_count=$((retry_count + 1))
    if [ $retry_count -ge $max_retries ]; then
        echo "Spark Master failed to start after $max_retries attempts. Exiting."
        docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-master
        exit 1
    fi
done
echo "Spark Master is ready."

# Start Spark job container
echo "Starting Spark job container..."
docker compose -f "$DOCKER_COMPOSE_FILE" up -d spark-job

# Wait for Spark job container to start
echo "Waiting for Spark job container to start..."
sleep 10

# Copy HDFS configuration files to Spark container
if docker cp ./core-site.xml spark-job:/opt/bitnami/spark/conf/core-site.xml; then
    echo "core-site.xml is copied."
else
    echo "Error: core-site.xml was not copied to Spark container."
    exit 1
fi
if docker cp ./hdfs-site.xml spark-job:/opt/bitnami/spark/conf/hdfs-site.xml; then
    echo "hdfs-site.xml is copied."
else
    echo "Error: hdfs-site.xml was not copied to Spark container."
    exit 1
fi

# Clean up
rm ./core-site.xml ./hdfs-site.xml
echo "Temporary files removed."

# Verify configuration files before starting the Spark Job
if ! docker exec spark-job test -f /opt/bitnami/spark/conf/core-site.xml || ! docker exec spark-job test -f /opt/bitnami/spark/conf/hdfs-site.xml; then
    echo "Error: HDFS configuration files are missing in the Spark container."
    exit 1
fi

# Start the Spark job (if needed)
echo "Starting Spark job..."
docker compose -f "$DOCKER_COMPOSE_FILE" restart spark-job

# Wait for Spark job to start
echo "Waiting for Spark job to start..."
sleep 20

# Check if Spark job is running
echo "Checking if Spark job is running..."
if docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-job | grep -q "ApplicationStateChanged"; then
    echo "Spark job is running."
else
    echo "Error: Spark job failed to start. Check the logs for more information."
    docker compose -f "$DOCKER_COMPOSE_FILE" logs spark-job
    exit 1
fi
