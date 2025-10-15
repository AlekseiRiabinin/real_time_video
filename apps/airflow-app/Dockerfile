FROM apache/airflow:2.9.1-python3.10

USER root

# Install OS-level packages for PostgreSQL, HDFS, Kafka and ClickHouse
RUN apt-get update && apt-get install -y \
    gcc \
    libpq-dev \
    krb5-user \
    libkrb5-dev \
    curl \
    libcurl4-openssl-dev \
    librdkafka-dev \
    unixodbc-dev \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

USER airflow

ARG AIRFLOW_VERSION=2.9.1
ARG CONSTRAINT_URL="https://raw.githubusercontent.com/apache/airflow/constraints-${AIRFLOW_VERSION}/constraints-3.10.txt"

# Install Python packages for Postgres + WebHDFS + PyArrow + Kafka
RUN pip install --no-cache-dir \
    --constraint "${CONSTRAINT_URL}" \
    apache-airflow-providers-postgres \
    apache-airflow-providers-apache-hdfs \
    apache-airflow-providers-apache-kafka \
    pyarrow

# Try alternative ClickHouse package names or install without constraints
RUN pip install --no-cache-dir \
    apache-airflow-providers-clickhouse==3.5.0 || \
    pip install --no-cache-dir \
    airflow-provider-clickhouse || \
    echo "ClickHouse provider not available, skipping..."

# Install additional Python packages
RUN pip install --no-cache-dir \
    hdfs \
    clickhouse-driver \
    clickhouse-connect
