
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.producer.KafkaProducer;

class History {

    static String mandatoryEnv(String key) throws Exception {
        final var v = System.getenv(key);
        if(v == null || v.length() == 0) throw new Exception("no "+key);
        return v;
    }

    public static void main(String[] args) {
        try{
            final var conf = new HashMap<String, Object>();
            final var confLine = mandatoryEnv("C4KAFKA_CONFIG");
            final var confLines = confLine.split(confLine.substring(0,1),-1);
            for(var i = 1; i < confLines.length; i+=3) switch(confLines[i]){
                case "C": conf.put(confLines[i+1], confLines[i+2]); break;
                case "E": conf.put(confLines[i+1], Files.readString(Paths.get(mandatoryEnv(confLines[i+2])))); break;
                default: throw new Exception("bad config");
            }
            //
            final var topicName = "bash_history";
            //
            conf.put("enable.auto.commit","false");
            final var deserializer = new StringDeserializer();
            //
            final var consumerDataPath = Paths.get(mandatoryEnv("C4HISTORY_GET"));
            Files.writeString(consumerDataPath,"");
            //
            final var serializer = new StringSerializer();
            final var producerDataPath = Paths.get(mandatoryEnv("C4HISTORY_PUT"));
            //
            try(final var consumer = new KafkaConsumer<String,String>(conf,deserializer,deserializer)){
                try(final var producer = new KafkaProducer<String,String>(conf, serializer, serializer)) {
                    Runtime.getRuntime().addShutdownHook(new Thread(consumer::wakeup));
                    final var partitions = new ArrayList<TopicPartition>();
                    partitions.add(new TopicPartition(topicName, 0));
                    consumer.assign(partitions);
                    consumer.seekToBeginning(partitions);
                    while (true) {
                        for (final var record : consumer.poll(Duration.ofMillis(1000))) {
                            if (record != null)
                                Files.writeString(consumerDataPath, record.value(), StandardOpenOption.APPEND);
                        }
                        if (Files.exists(producerDataPath)) {
                            final var value = Files.readString(producerDataPath);
                            Files.delete(producerDataPath);
                            final var record = new ProducerRecord<String, String>(topicName, value);
                            final RecordMetadata meta = producer.send(record).get();
                            System.out.println(meta.offset());
                        }
                    }
                }
            }
        } catch(Exception e){
            e.printStackTrace();
            System.exit(1);
        }
    }
}
