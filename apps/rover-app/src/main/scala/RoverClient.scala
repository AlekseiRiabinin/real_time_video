import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties


val props = new Properties()
props.put("bootstrap.servers", "first-machine-ip:9092")
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")

val producer = new KafkaProducer[String, Array[Byte]](props)

val videoBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("path/to/video.mp4"))
val record = new ProducerRecord[String, Array[Byte]]("video-topic", videoBytes)

producer.send(record)
producer.close()
