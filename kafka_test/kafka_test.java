
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.stream.IntStream;

import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.ConfigResource.Type;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.RecordsToDelete;

class KafkaTest {
    String mandatoryEnv(String key) throws Exception {
        final var v = System.getenv(key);
        if(v == null || v.length() == 0) throw new Exception("no "+key);
        return v;
    }
    Map<String, Object> getConf() throws Exception {
        final var keyPassPath = mandatoryEnv("C4STORE_PASS_PATH");
        final var keyPass = Files.readString(Paths.get(keyPassPath));
        final var conf = new HashMap<String, Object>();
        conf.put("bootstrap.servers", mandatoryEnv("C4BOOTSTRAP_SERVERS"));
        conf.put("security.protocol", "SSL");
        conf.put("ssl.keystore.location", mandatoryEnv("C4KEYSTORE_PATH"));
        conf.put("ssl.keystore.password", keyPass);
        conf.put("ssl.key.password", keyPass);
        conf.put("ssl.truststore.location", mandatoryEnv("C4TRUSTSTORE_PATH"));
        conf.put("ssl.truststore.password", keyPass);
        conf.put("ssl.endpoint.identification.algorithm", "");
        return conf;
    }
    Map<String, Object> getConsumerConf() throws Exception {
        final var conf = getConf();
        conf.put("enable.auto.commit", "false");
        conf.put("group.id", "test");
        return conf;
    }

    KafkaConsumer<String,String> createConsumer(Map<String, Object> conf) {
        final var deserializer = new StringDeserializer();
        final var consumer = new KafkaConsumer<String, String>(conf, deserializer, deserializer);
        return consumer;
    }
    KafkaProducer<String,String> createProducer(Map<String, Object> conf) {
        final var serializer = new StringSerializer();
        final var producer = new KafkaProducer<String,String>(conf, serializer, serializer);
        return producer;
    }

    void printOffsets(Map<TopicPartition,java.lang.Long> offsets) {
        offsets.forEach((k,v)->{
            System.out.println(" "+k+" "+v+" "+Long.toHexString(v));
        });
    }

    void testStartEndPositions() throws Exception {
        final var topicName = mandatoryEnv("C4INBOX_TOPIC_PREFIX") + ".inbox";
        try (final var consumer = createConsumer(getConsumerConf())) {
            final var partitions = Collections.singleton(new TopicPartition(topicName, 0));
            consumer.assign(partitions);
            //
            printOffsets(consumer.beginningOffsets(partitions));
            printOffsets(consumer.endOffsets(partitions));
        }
    }

    Map<ConfigResource, Config> getRetentionTimeUpdateCommand(String topicName, long retentionTime) {
        return Map.of(
                new ConfigResource(Type.TOPIC, topicName),
                new Config(Collections.singleton(new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retentionTime))))
        );
    }

    String getTestTopicName() {
        return "test5";
    }

    void testProduceManyRecords() throws Exception {
        try(final var producer = createProducer(getConf())) {
            IntStream.range(0, 1000000).forEachOrdered(n -> {
                final var record = new ProducerRecord<String, String>(getTestTopicName(), "" + n);
                producer.send(record);
            });
        }
    }

    void testMinLife() throws Exception {
        final var topicName = getTestTopicName();

        testProduceManyRecords();

        try(final var client = AdminClient.create(getConf())) {
            client.alterConfigs(getRetentionTimeUpdateCommand(topicName, 1000)).all().get();
        }

        // Thread.sleep(5000);
        try (final var consumer = createConsumer(getConsumerConf())) {
            final var partition = new TopicPartition(topicName, 0);
            consumer.commitSync(Map.of(partition,new OffsetAndMetadata(400000))); // this does NOT prevent msg cleanup
        }
    }
    void testOffsetsRes() throws Exception {
        final var topicName = getTestTopicName();

        try (final var consumer = createConsumer(getConsumerConf())) {
            final var partition = new TopicPartition(topicName, 0);
            final var partitions = Collections.singleton(partition);
            printOffsets(consumer.beginningOffsets(partitions));
            printOffsets(consumer.endOffsets(partitions));
        }
    }

    void testForceDel() throws Exception {
        final var topicName = getTestTopicName();
        testProduceManyRecords();
        try(final var client = AdminClient.create(getConf())) {
            client.alterConfigs(getRetentionTimeUpdateCommand(topicName, -1)).all().get();
            client.deleteRecords(Map.of(new TopicPartition(topicName, 0), RecordsToDelete.beforeOffset(500000))).all().get();
            // works
            // so offset of 1st rec to keep will be 500000
        }
    }

    void testHead() throws Exception {
        final var topicName = getTestTopicName();
        try (final var consumer = createConsumer(getConsumerConf())) {
            final var partition = new TopicPartition(topicName, 0);
            final var partitions = Collections.singleton(partition);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            final var record = consumer.poll(1000).iterator().next();
            System.out.println(record.offset());
        }
    }

    public static void main(String[] args) {
        try {
            switch(args[0]){
                case "testInboxStartEndPositions" -> new KafkaTest().testStartEndPositions();
                case "testMinLife" -> new KafkaTest().testMinLife();
                case "testOffsetsRes" -> new KafkaTest().testOffsetsRes();
                case "testForceDel" -> new KafkaTest().testForceDel();
                case "testHead" -> new KafkaTest().testHead();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    /*
    put many messages
    set minimal retention
    wait a bit
    see start pos
    */
}