# Use a pre-built base image with Java 17
FROM eclipse-temurin:17-jdk
USER root

# Install Hadoop dependencies
RUN apt-get update && \
    apt-get install -y wget ssh pdsh && \
    rm -rf /var/lib/apt/lists/*

# Download and install Hadoop 3.3.6
RUN wget https://downloads.apache.org/hadoop/common/hadoop-3.3.6/hadoop-3.3.6.tar.gz && \
    tar -xzvf hadoop-3.3.6.tar.gz -C /usr/local/ && \
    rm hadoop-3.3.6.tar.gz && \
    ln -s /usr/local/hadoop-3.3.6 /usr/local/hadoop

# Set environment variables
ENV HADOOP_HOME=/usr/local/hadoop
ENV PATH=$HADOOP_HOME/bin:$PATH

# Configure SSH for Hadoop
RUN ssh-keygen -t rsa -P '' -f ~/.ssh/id_rsa && \
    cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys && \
    chmod 0600 ~/.ssh/authorized_keys

# Create Hadoop user and set permissions
RUN useradd -m -s /bin/bash hadoop && \
    chown -R hadoop:hadoop $HADOOP_HOME && \
    mkdir -p /usr/local/hadoop/logs && \
    chown -R hadoop:hadoop /usr/local/hadoop/logs

# Create necessary directories
RUN mkdir -p /hadoop/dfs/name && \
    mkdir -p /tmp/hadoop && \
    chown -R hadoop:hadoop /hadoop && \
    chown -R hadoop:hadoop /tmp

# Copy configuration files
COPY ./core-site.xml $HADOOP_HOME/etc/hadoop/core-site.xml
COPY ./hdfs-site.xml $HADOOP_HOME/etc/hadoop/hdfs-site.xml

# Switch to hadoop user
USER hadoop

# Expose ports
EXPOSE 50070 8020

# Command to run DataNode
CMD ["hdfs", "namenode"]

# # Build the image
# docker build -f hadoop-namenode/Dockerfile -t alexflames77/custom-hadoop-namenode:3.3.6-java17 .

# # Push the image with the first tag
# docker push alexflames77/custom-hadoop-namenode:3.3.6-java17
