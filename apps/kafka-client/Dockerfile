# Use an official OpenJDK 21 image as a parent image
FROM eclipse-temurin:21-jdk

# Set the working directory in the container
WORKDIR /app

# Install necessary packages (e.g., FFmpeg for video processing)
RUN apt-get update && apt-get install -y ffmpeg

# Copy the pre-built JAR file to the container
COPY target/scala-3.3.4/KafkaClient-assembly-0.1.0-SNAPSHOT.jar /app/kafka-client.jar

# Expose Prometheus metrics port
EXPOSE 9091

# Run the application
CMD ["java", "-jar", "/app/kafka-client.jar"]
