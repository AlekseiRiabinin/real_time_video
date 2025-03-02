# Use an official OpenJDK 21 image as a parent image
FROM eclipse-temurin:21-jdk

# Set the working directory in the container
WORKDIR /app

# Install necessary packages (e.g., FFmpeg for video processing)
RUN apt-get update && apt-get install -y ffmpeg

# Create Hadoop home directory
RUN mkdir -p /opt/hadoop

# Set Hadoop configuration directory and home
ENV HADOOP_CONF_DIR=/etc/hadoop
ENV HADOOP_HOME=/opt/hadoop
ENV PATH=$PATH:$HADOOP_HOME/bin

# Set environment variables for Kafka and HDFS
ENV KAFKA_BOOTSTRAP_SERVERS="kafka-1:9092,kafka-2:9095"
ENV HDFS_URI="hdfs://namenode:8020"

# Copy the pre-built JAR file to the container
COPY target/scala-3.3.4/KafkaClient-assembly-0.1.0-SNAPSHOT.jar /app/kafka-client.jar

# Expose Prometheus metrics port
EXPOSE 9080

# Run the application
# CMD ["java", "-jar", "/app/kafka-client.jar"]
CMD ["java", "-cp", "/app/kafka-client.jar:/etc/hadoop/*", "KafkaClient"]
