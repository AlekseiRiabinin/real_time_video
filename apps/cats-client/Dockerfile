# Use an official OpenJDK 21 image as the base
FROM eclipse-temurin:21-jdk-jammy

# Set the working directory in the container
WORKDIR /app

# Install necessary packages
RUN apt-get update && \
    apt-get install -y ffmpeg libopencv-dev && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create Hadoop home directory (optional, if needed)
RUN mkdir -p /opt/hadoop

# Set Hadoop configuration directory and home (optional, if needed)
ENV HADOOP_CONF_DIR=/etc/hadoop
ENV HADOOP_HOME=/opt/hadoop
ENV PATH=$PATH:$HADOOP_HOME/bin

# Set environment variables for Kafka and HDFS
ENV KAFKA_BOOTSTRAP_SERVERS="kafka-1:9092,kafka-2:9095"
ENV HDFS_URI="hdfs://namenode:8020"

# Copy the pre-built JAR file to the container
COPY target/scala-3.3.4/cats-client-assembly-0.1.0-SNAPSHOT.jar /app/cats-client.jar

# Copy Hadoop configuration files (optional, if needed)
COPY hadoop-config/core-site.xml /etc/hadoop/core-site.xml
COPY hadoop-config/hdfs-site.xml /etc/hadoop/hdfs-site.xml

# Expose Prometheus metrics port
EXPOSE 9082

# Run the application
CMD ["java", "-jar", "/app/cats-client.jar"]
