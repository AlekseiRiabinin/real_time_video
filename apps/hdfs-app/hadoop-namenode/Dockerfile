FROM bde2020/hadoop-namenode:2.0.0-hadoop3.3.6-java8
USER root

# Install Java 11
RUN apt-get update && \
    apt-get install -y openjdk-11-jdk && \
    update-alternatives --set java /usr/lib/jvm/java-11-openjdk-amd64/bin/java && \
    update-alternatives --set javac /usr/lib/jvm/java-11-openjdk-amd64/bin/javac

USER hadoop

# docker build -t custom-hadoop-namenode:3.3.6-java11 .
