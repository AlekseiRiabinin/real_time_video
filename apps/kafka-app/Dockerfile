# Use an official OpenJDK 21 image as a parent image
FROM eclipse-temurin:21-jdk

# Set the working directory in the container
WORKDIR /app

# Install necessary packages
RUN apt-get update && apt-get install -y wget unzip ffmpeg

# Copy the pre-built JAR file
COPY target/scala-3.3.4/KafkaService-assembly-0.1.0-SNAPSHOT.jar /app/

# Run the application
CMD ["java", "-jar", "/app/KafkaService-assembly-0.1.0-SNAPSHOT.jar"]
