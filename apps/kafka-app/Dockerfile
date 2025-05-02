# Use an official OpenJDK 21 image as a parent image
FROM eclipse-temurin:21-jdk-

# Set the working directory in the container
WORKDIR /app

# Copy the pre-built JAR file
COPY target/scala-3.3.4/KafkaService-assembly-0.1.0-SNAPSHOT.jar /app/kafka-service.jar

# Run the application
CMD ["java", "-jar", "/app/kafka-service.jar"]
