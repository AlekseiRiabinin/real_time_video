FROM openjdk:17-jdk-slim

COPY target/scala-3.5.2/kafka-app-assembly-0.1.0.jar /app/kafka-app.jar

ENTRYPOINT ["java", "-jar", "/app/kafka-app.jar"]