#!/bin/sh

kafka-topics.sh --create --topic __consumer_offsets --partitions 3 --replication-factor 2 --bootstrap-server 172.18.0.2:9092
kafka-topics.sh --create --topic video-stream --partitions 1 --replication-factor 2 --bootstrap-server 172.18.0.2:9092 

# Keep the container running
tail -f /dev/null