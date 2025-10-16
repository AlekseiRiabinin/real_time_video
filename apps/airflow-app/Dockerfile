FROM apache/airflow:2.9.1-python3.10

USER root

# Install OS-level packages for PostgreSQL, HDFS, Kafka, Spark and ClickHouse
RUN apt-get update && apt-get install -y \
    gcc \
    libpq-dev \
    krb5-user \
    libkrb5-dev \
    curl \
    wget \
    libcurl4-openssl-dev \
    librdkafka-dev \
    unixodbc-dev \
    default-jre-headless \
    openssh-client \
    procps \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

USER airflow

ARG AIRFLOW_VERSION=2.9.1
ARG CONSTRAINT_URL="https://raw.githubusercontent.com/apache/airflow/constraints-${AIRFLOW_VERSION}/constraints-3.10.txt"

# Install Airflow providers with constraints (main providers)
RUN pip install --no-cache-dir \
    --constraint "${CONSTRAINT_URL}" \
    apache-airflow-providers-postgres \
    apache-airflow-providers-apache-hdfs \
    apache-airflow-providers-apache-kafka \
    apache-airflow-providers-apache-spark \
    pyarrow

# Install ClickHouse provider
RUN pip install --no-cache-dir \
    airflow-provider-clickhouse

# Install additional Python packages for data processing
RUN pip install --no-cache-dir \
    hdfs \
    clickhouse-driver \
    clickhouse-connect \
    pyspark==3.5.0 \
    pandas \
    numpy \
    boto3

# Set environment variables for Spark
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV SPARK_HOME=/home/airflow/.local/lib/python3.10/site-packages/pyspark
ENV PATH=$JAVA_HOME/bin:$SPARK_HOME/bin:$PATH

# Create directories for Spark logs and configs (simplified - no chown needed)
USER root
RUN mkdir -p /opt/spark/logs
USER airflow
