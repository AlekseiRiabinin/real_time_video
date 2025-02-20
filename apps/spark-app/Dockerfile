FROM bitnami/spark:3.5.4

COPY target/scala-2.12/SparkMLJob-assembly-0.1.0-SNAPSHOT.jar /opt/spark-app/spark-job.jar

ENTRYPOINT ["/opt/bitnami/spark/bin/spark-submit", "--class", "com.sparkml.SparkMLJob", "/opt/spark-app/spark-job.jar"]
