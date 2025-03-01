# Use an official OpenJDK 21 image as a parent image
FROM eclipse-temurin:21-jdk

# Set the working directory in the container
WORKDIR /app

# Install necessary packages (e.g., FFmpeg for video processing)
RUN apt-get update && apt-get install -y ffmpeg

# Install Hadoop
RUN wget https://downloads.apache.org/hadoop/common/hadoop-3.3.6/hadoop-3.3.6.tar.gz && \
    tar -xzvf hadoop-3.3.6.tar.gz && \
    mv hadoop-3.3.6 /opt/hadoop && \
    rm hadoop-3.3.6.tar.gz

# Set Hadoop environment variables
ENV HADOOP_HOME=/opt/hadoop
ENV PATH=$PATH:$HADOOP_HOME/bin

# Copy Hadoop configuration files
COPY hadoop-conf /etc/hadoop

# Set Hadoop configuration directory
ENV HADOOP_CONF_DIR=/etc/hadoop

# Copy the pre-built JAR file to the container
COPY target/scala-3.3.4/KafkaClient-assembly-0.1.0-SNAPSHOT.jar /app/kafka-client.jar

# Expose Prometheus metrics port
EXPOSE 9080

# Run the application
CMD ["java", "-jar", "/app/kafka-client.jar"]
