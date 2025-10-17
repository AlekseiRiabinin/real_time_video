# Dockerfile
FROM bitnami/spark:3.5.4

# Set environment variables
ENV SPARK_MASTER_URL="spark://spark-master:7077"
ENV SPARK_HADOOP_FS_DEFAULTFS="hdfs://namenode:8020"

# Copy configuration files into the conf directory
COPY spark-defaults.conf /opt/bitnami/spark/conf/spark-defaults.conf

# Copy the fat JAR into the container
COPY target/scala-2.12/spark-job-fat.jar /opt/spark-app/spark-job-fat.jar

# Set the entrypoint to run the fat JAR
ENTRYPOINT ["/opt/bitnami/spark/bin/spark-submit", \
            "--conf", "spark.jars.ivy=/opt/bitnami/spark/.ivy2", \
            "--class", "com.sparkml.SparkMLJob", \
            "/opt/spark-app/spark-job-fat.jar"]
