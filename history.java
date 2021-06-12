
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

    String mandatoryEnv(String key) throws Exception {
        final var v = System.getenv(key);
        if(v == null || v.length() == 0) throw new Exception("no "+key);
        return v;
    }

    Map<String,Object> getConf() throws Exception {
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
        return conf;
    }

    String getTopicName() {
        return "bash_history";
    }

    void runProducer() throws Exception {
        final var conf = getConf();
        final var serializer = new StringSerializer();
        final var dataPath = Paths.get(mandatoryEnv("C4HISTORY_PUT"));
        try(final var producer = new KafkaProducer<String,String>(conf, serializer, serializer)){
            while(true){
                if(Files.exists (dataPath)){
                    final var value = Files.readString(dataPath);
                    Files.delete(dataPath);
                    final var record = new ProducerRecord<String,String>(getTopicName(), value);
                    final RecordMetadata meta = producer.send(record).get();
                    System.out.println(meta.offset());
                } else {
                    Thread.sleep(1000);
                }
            }
        }
    }

    void runConsumer() throws Exception {
        final var conf = getConf();
        conf.put("enable.auto.commit","false");
        final var deserializer = new StringDeserializer();
        final var dataPath = Paths.get(mandatoryEnv("C4HISTORY_GET"));
        Files.writeString(dataPath,"");
        try(final var consumer = new KafkaConsumer<String,String>(conf,deserializer,deserializer)){
            Runtime.getRuntime().addShutdownHook(new Thread(consumer::wakeup));
            final var partitions = new ArrayList<TopicPartition>();
            partitions.add(new TopicPartition(getTopicName(),0));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            while(true){
                for(final var record: consumer.poll(Duration.ofMillis(200))){
                    if (record != null)
                        Files.writeString(dataPath, record.value(), StandardOpenOption.APPEND);
                }
            }
        }
    }

    public static void main(String[] args) {
        try{
            switch(args[0]){
                case "producer" -> new History().runProducer();
                case "consumer" -> new History().runConsumer();
            }
        } catch(Exception e){
            e.printStackTrace();
            System.exit(1);
        }
    }

}