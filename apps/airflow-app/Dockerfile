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
    hdfs \
    clickhouse-driver \
    clickhouse-connect \
    pyspark==3.5.4 \
    pandas \
    numpy \
    boto3 \
    pyodbc \
    pymssql

# Set environment variables for Spark
ENV SPARK_HOME=/home/airflow/.local/lib/python3.10/site-packages/pyspark
ENV PATH=$SPARK_HOME/bin:$PATH

# Set ODBC driver path for MSSQL
ENV ODBCSYSINI=/etc/
ENV ODBCINI=/etc/odbc.ini

# Create directories for Spark logs and configs
USER root
RUN mkdir -p /opt/spark/logs
USER airflow
