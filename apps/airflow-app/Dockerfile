FROM apache/airflow:2.9.1-python3.10

USER root

# Install OS-level packages for PostgreSQL, HDFS, Kafka, Spark, ClickHouse and MSSQL
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
    default-jdk \
    openssh-client \
    procps \
    gnupg2 \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Install Microsoft ODBC Driver 18 for SQL Server
RUN curl https://packages.microsoft.com/keys/microsoft.asc | apt-key add - \
 && curl https://packages.microsoft.com/config/debian/11/prod.list > /etc/apt/sources.list.d/mssql-release.list \
 && apt-get update \
 && ACCEPT_EULA=Y apt-get install -y msodbcsql18 \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Register ODBC driver and create configuration
RUN echo "[ODBC Driver 18 for SQL Server]" > /etc/odbcinst.ini \
 && echo "Description=Microsoft ODBC Driver 18 for SQL Server" >> /etc/odbcinst.ini \
 && echo "Driver=/opt/microsoft/msodbcsql18/lib64/libmsodbcsql-18.so" >> /etc/odbcinst.ini \
 && echo "UsageCount=1" >> /etc/odbcinst.ini

# Create ODBC.ini file
RUN echo "[ODBC Driver 18 for SQL Server]" > /etc/odbc.ini \
 && echo "Description=Microsoft ODBC Driver 18 for SQL Server" >> /etc/odbc.ini \
 && echo "Driver=/opt/microsoft/msodbcsql18/lib64/libmsodbcsql-18.so" >> /etc/odbc.ini \
 && echo "UsageCount=1" >> /etc/odbc.ini

# Install full Spark distribution
RUN wget -q https://archive.apache.org/dist/spark/spark-3.5.4/spark-3.5.4-bin-hadoop3.tgz \
    && tar -xzf spark-3.5.4-bin-hadoop3.tgz -C /opt/ \
    && mv /opt/spark-3.5.4-bin-hadoop3 /opt/spark \
    && rm spark-3.5.4-bin-hadoop3.tgz

# Create directories for Spark logs and configs
RUN mkdir -p /opt/spark/logs /opt/spark/work

# Set permissions - airflow user will be available after we switch
RUN chmod -R 755 /opt/spark

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
    apache-airflow-providers-microsoft-mssql \
    pyarrow

# Install ClickHouse provider
RUN pip install --no-cache-dir \
    airflow-provider-clickhouse

# Install additional Python packages for data processing
RUN pip install --no-cache-dir \
    hdfs==2.7.3 \
    clickhouse-connect==0.7.7 \
    clickhouse-driver==0.2.6 \
    pyspark==3.5.4 \
    pandas==2.0.3 \
    numpy==1.24.3 \
    boto3==1.28.62 \
    pyodbc==4.0.39 \
    pymssql==2.2.7 \
    redis==4.5.5 \
    celery==5.3.4 \
    kombu==5.3.4

# Set environment variables for Spark
ENV SPARK_HOME=/opt/spark
ENV PATH=$SPARK_HOME/bin:$PATH
ENV PYTHONPATH=$SPARK_HOME/python:$SPARK_HOME/python/lib/py4j-0.10.9.7-src.zip

# Set ODBC driver path for MSSQL
ENV ODBCSYSINI=/etc/
ENV ODBCINI=/etc/odbc.ini
