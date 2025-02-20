#!/bin/bash

# Step 1: Set up environment variables
APP_NAME="MarsImageClassification"
SCALA_VERSION="2.12"
ASSEMBLY_JAR="mars-image-classification-assembly.jar"
JAR_PATH="target/scala-$SCALA_VERSION/$ASSEMBLY_JAR"
CONFIG_FILE="application.conf"

# Step 2: Check if SBT is installed
if ! command -v sbt &> /dev/null; then
    echo "SBT is not installed. Please install SBT and try again."
    exit 1
fi

# Step 3: Clean and build the project using SBT
echo "Cleaning and building the project..."
sbt marsImageClassification/assembly

# Check if the JAR file was created
if [ ! -f "$JAR_PATH" ]; then
    echo "Failed to build the JAR file. Please check the build logs."
    exit 1
fi

echo "Build successful. JAR file created at: $JAR_PATH"

# Step 4: Run the Spark application
echo "Running the Spark application..."
spark-submit \
  --class MarsImageClassification \
  --master local[*] \
  --driver-memory 4G \
  "$JAR_PATH"

# Step 5: Verify the execution
if [ $? -eq 0 ]; then
    echo "Spark application executed successfully."
else
    echo "Spark application failed. Please check the logs for errors."
    exit 1
fi
