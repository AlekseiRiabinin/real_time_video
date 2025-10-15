FROM apache/airflow:2.9.1-python3.10

USER root

# Install OS-level packages for PostgreSQL, HDFS + krb5 if needed
RUN apt-get update && apt-get install -y \
    gcc \
    libpq-dev \
    krb5-user \
    libkrb5-dev \
    curl \
    libcurl4-openssl-dev \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

USER airflow

ARG AIRFLOW_VERSION=2.9.1
ARG CONSTRAINT_URL="https://raw.githubusercontent.com/apache/airflow/constraints-${AIRFLOW_VERSION}/constraints-3.10.txt"

# Install Python packages for Postgres + WebHDFS + PyArrow
RUN pip install --no-cache-dir \
    --constraint "${CONSTRAINT_URL}" \
    apache-airflow-providers-postgres \
    apache-airflow-providers-apache-hdfs \
    apache-airflow-providers-clickhouse \
    apache-airflow-providers-apache-kafka \
    pyarrow \
    hdfs
