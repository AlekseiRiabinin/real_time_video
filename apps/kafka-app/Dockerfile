# Use an official OpenJDK 21 image as a parent image
FROM eclipse-temurin:21-jdk

# Set the working directory in the container
WORKDIR /app

# Install necessary packages
RUN apt-get update && apt-get install -y wget unzip

# # Download and extract OpenCV native libraries
# RUN wget https://repo1.maven.org/maven2/org/bytedeco/opencv/4.10.0-1.5.11/opencv-4.10.0-1.5.11-linux-x86_64.jar -O opencv.jar && \
# unzip opencv.jar -d /usr/local/opencv && \
# rm opencv.jar

# # Verify OpenCV installation
# RUN ls /usr/local/opencv

# # Set the library path
# ENV LD_LIBRARY_PATH=/usr/local/opencv

# Copy the pre-built JAR file
COPY target/scala-3.3.4/KafkaService-assembly-0.1.0-SNAPSHOT.jar /app/

# Run the application
CMD ["java", "-jar", "/app/KafkaService-assembly-0.1.0-SNAPSHOT.jar"]
