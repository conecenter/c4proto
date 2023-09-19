
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
            final var keyPassPath = mandatoryEnv("C4STORE_PASS_PATH");
            final var keyPass = Files.readString(Paths.get(keyPassPath));
            final var conf = new HashMap<String, Object>();
            conf.put("bootstrap.servers",mandatoryEnv("C4BOOTSTRAP_SERVERS"));
            conf.put("security.protocol","SSL");
            conf.put("ssl.keystore.location",mandatoryEnv("C4KEYSTORE_PATH"));
            conf.put("ssl.keystore.password",keyPass);
            conf.put("ssl.key.password",keyPass);
            conf.put("ssl.truststore.location",mandatoryEnv("C4TRUSTSTORE_PATH"));
            conf.put("ssl.truststore.password",keyPass);
            conf.put("ssl.endpoint.identification.algorithm","");
            //
            final var topicName = "test0";
            //
            conf.put("enable.auto.commit","false");
            //
            final var serializer = new StringSerializer();
            //
            try(final var producer = new KafkaProducer<String,String>(conf, serializer, serializer)) {
                final var value = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
                while (true) {
                    final var started = System.currentTimeMillis();
                    final var record = new ProducerRecord<String, String>(topicName, value);
                    final RecordMetadata meta = producer.send(record).get();
                    final var period = System.currentTimeMillis() - started;
                    System.out.print(".");
                    if(period > 500) System.out.println(period);
                    //System.out.println(meta.offset());
                    Thread.sleep(100);
                }
            }
        } catch(Exception e){
            e.printStackTrace();
            System.exit(1);
        }
    }
}

// export CLASSPATH=/c4/app/kafka-clients-2.3.0.jar:/c4/app/slf4j-api-1.7.30.jar