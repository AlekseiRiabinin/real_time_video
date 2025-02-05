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
ENV HADOOP_HOME /usr/local/hadoop
ENV PATH $HADOOP_HOME/bin:$PATH

# Configure SSH for Hadoop
RUN ssh-keygen -t rsa -P '' -f ~/.ssh/id_rsa && \
    cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys && \
    chmod 0600 ~/.ssh/authorized_keys

# Create Hadoop user
RUN useradd -m hadoop
USER hadoop

# Expose ports
EXPOSE 50070 8020 9000

# Command to run DataNode
CMD ["hdfs", "namenode"]

# EXPOSE 50070 8020 9000
# docker build -t custom-hadoop-namenode:3.3.6-java17 .
