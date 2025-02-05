# Use the official Flink image as the base image
FROM flink:1.17.1

# Set the working directory
WORKDIR /app

# Copy the built JAR file into the container
COPY target/scala-2.12/FlinkJob-assembly-0.1.0-SNAPSHOT.jar /app/

# Copy the flink-conf.yaml file into the container
COPY flink-conf.yaml /opt/flink/conf/

# Copy the consumer.properties file into the container
COPY consumer.properties /app/config/

# Set the entry point to run the Flink job
ENTRYPOINT ["flink", "run", "/app/FlinkJob-assembly-0.1.0-SNAPSHOT.jar"]
