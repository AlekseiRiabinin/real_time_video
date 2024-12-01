# Real-Time Video Streaming Service

![Alt text](images/real_time_video_hld.jpg)

## 1. Ingestion with Kafka

1. <u>Data Ingestion</u>: Kafka acts as the entry point for the video data. It ingests the video stream from the camera.

2. <u>Buffering</u>: Kafka provides a durable and scalable buffer for the video data, ensuring that even if there are temporary disruptions, the data is not lost.

3. <u>Message Broker</u>: Kafka serves as a message broker, allowing multiple consumers (like Flink and Spark) to read the video data streams in real-time.

<b>Process</b>:
•  The camera streams video data using a tool like FFmpeg, which sends the video stream to a Kafka topic.
•  Kafka brokers receive and store the video data in topics, partitioning the data for scalability and fault tolerance.


## 2. Real-Time Processing with Flink

1. <u>Stream Processing</u>: Flink consumes the video data from Kafka topics and processes it in real-time. This can include tasks like video frame analysis, filtering, and transformation.

2. <u>Stateful Computations</u>: Flink maintains state across the stream processing tasks, which is crucial for complex event processing and analytics.

3. <u>Low-Latency Processing</u>: Flink ensures low-latency processing of the video streams, making it suitable for real-time applications.

<b>Process</b>:
•  A Flink job is deployed on the Kubernetes cluster. This job consumes video data from Kafka topics.
•  The Flink job processes each video frame or chunk of data, applying the necessary transformations or analytics.
•  Processed data is then published back to Kafka for further processing.


## 3. Intermediate Storage with Kafka

1. <u>Buffering</u>: Kafka stores the processed video data from Flink, ensuring it is available for further processing by Spark.

2. <u>Message Broker</u>: Kafka continues to act as a message broker, allowing Spark to consume the processed data.

<b>Process</b>:
•  The processed video data from Flink is published to a new Kafka topic.
•  Kafka brokers store this data, making it available for Spark to consume.


## 4. Machine Learning with Spark


1. <u>Batch Processing</u>: Spark consumes the processed video data from Kafka and performs batch processing tasks.

2. <u>Machine Learning</u>: Spark uses its MLlib library to train machine learning models in real-time on the video data.

3. <u>Advanced Analytics</u>: Spark performs advanced analytics on the video data, such as anomaly detection, pattern recognition, and predictive analytics.

<b>Process</b>:
•  A Spark job is deployed on the Kubernetes cluster. This job consumes the processed video data from Kafka topics.
•  The Spark job processes the data, trains machine learning models, and performs advanced analytics.


## 5. Long-Term Storage with HDFS

After processing the data in Spark, the final processed data is written to HDFS for long-term storage.
