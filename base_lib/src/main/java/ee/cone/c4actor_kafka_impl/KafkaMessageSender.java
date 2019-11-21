package ee.cone.c4actor_kafka_impl;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class KafkaMessageSender {
    public static Future<RecordMetadata> send(
            String topic,
            byte[] value,
            List<Header> headers,
            CompletableFuture<Producer<byte[], byte[]>> producer
    ) throws ExecutionException, InterruptedException {
        return producer.get().send(new ProducerRecord<>(topic, 0, new byte[0], value, headers));
    }
}
