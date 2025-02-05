FROM bitnami/spark:3.5.4

COPY target/scala-2.12/spark-ml-job-assembly.jar /opt/spark-apps/

ENTRYPOINT ["/opt/bitnami/spark/bin/spark-submit", "--class", "SparkMLJob", "/opt/spark-apps/spark-ml-assembly-job.jar"]
