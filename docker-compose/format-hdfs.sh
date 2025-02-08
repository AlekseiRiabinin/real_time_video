# Function to format NameNode and clear DataNode
echo "Formatting NameNode and clearing DataNode..."

# Stop existing containers
docker compose -f docker-compose.app.yml down

# Remove Docker volumes for NameNode and DataNode
docker volume rm -f $(docker volume ls -q -f name=namenode-data)
docker volume rm -f $(docker volume ls -q -f name=datanode-data)

# Format NameNode
docker compose -f docker-compose.app.yml run --rm namenode hdfs namenode -format -force
if [ $? -ne 0 ]; then
    echo "Failed to format NameNode. Exiting."
    exit 1
fi

echo "NameNode formatted successfully."
