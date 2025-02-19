FROM bitnami/spark:3.5.4

COPY apps/sparkMLJob/target/scala-2.12/spark-ml-job-assembly.jar /opt/spark-app/spark-job.jar

ENTRYPOINT ["/opt/bitnami/spark/bin/spark-submit", "--class", "com.sparkml.SparkMLJob", "/opt/spark-app/spark-job.jar"]
