package ingestion

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecords}
import java.util.Properties
import scala.collection.JavaConverters._

object KafkaService {
    def main(args: Array[String]): Unit = {
        val producer = createProducer()
        val consumer = createConsumer()

        // Start the producer and consumer in separate threads
        new Thread(() => runProducer(producer)).start()
        new Thread(() => runConsumer(consumer)).start()
    }

    def createProducer(): KafkaProducer[String, String] = {
        val props = new Properties()
        props.put("bootstrap.servers", "kafka.kafka-namespace.svc.cluster.local:9092")
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        new KafkaProducer[String, String](props)
    }

    def runProducer(producer: KafkaProducer[String, String]): Unit = {
        while (true) {
            val record = new ProducerRecord[String, String]("your-topic", s"key-${System.currentTimeMillis()}", s"value-${System.currentTimeMillis()}")
            producer.send(record)
            println(s"Sent record(key=${record.key()} value=${record.value()})")
            Thread.sleep(1000) // Adjust the sleep time as needed
        }
    }

    def createConsumer(): KafkaConsumer[String, String] = {
        val props = new Properties()
        props.put("bootstrap.servers", "kafka.kafka-namespace.svc.cluster.local:9092")
        props.put("group.id", "consumer-group")
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        new KafkaConsumer[String, String](props)
    }

    def runConsumer(consumer: KafkaConsumer[String, String]): Unit = {
        consumer.subscribe(List("your-topic").asJava)
        while (true) {
            val records: ConsumerRecords[String, String] = consumer.poll(1000)
            for (record <- records.asScala) {
                println(s"Consumed record(key=${record.key()} value=${record.value()})")
            }
        }
    }
}