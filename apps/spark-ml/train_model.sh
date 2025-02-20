#!/bin/bash

APP_NAME="MarsImageClassification"
SCALA_VERSION="2.12"
ASSEMBLY_JAR="MarsImageClassification-assembly-0.1.0-SNAPSHOT.jar"
JAR_PATH="target/scala-$SCALA_VERSION/$ASSEMBLY_JAR"
CONFIG_FILE="application.conf"

# if ! command -v sbt &> /dev/null; then
#     echo "SBT is not installed. Please install SBT and try again."
#     exit 1
# fi

# echo "Cleaning and building the project..."
# sbt marsImageClassification/assembly

# Check if the JAR file was created
if [ ! -f "$JAR_PATH" ]; then
    echo "Failed to build the JAR file. Please check the build logs."
    exit 1
fi

echo "Build successful. JAR file created at: $JAR_PATH"

# Run the Spark application
echo "Running the Spark application..."
spark-submit \
  --class MarsImageClassification \
  --master local[*] \
  --driver-memory 8g \
  --executor-memory 8g \
  "$JAR_PATH"

# Verify the execution
if [ $? -eq 0 ]; then
    echo "Spark application executed successfully."
else
    echo "Spark application failed. Please check the logs for errors."
    exit 1
fi
