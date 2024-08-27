
import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

String mandatoryEnv(String key) throws Exception {
    final var v = System.getenv(key);
    if(v == null || v.length() == 0) throw new Exception("no "+key);
    return v;
}
HashMap<String, Object> readConf() throws Exception {
    final var conf = new HashMap<String, Object>();
    final var confLine = mandatoryEnv("C4KAFKA_CONFIG");
    final var confLines = confLine.split(confLine.substring(0,1),-1);
    for(var i = 1; i < confLines.length; i+=3) switch(confLines[i]){
        case "C": conf.put(confLines[i+1], confLines[i+2]); break;
        case "E": conf.put(confLines[i+1], Files.readString(Paths.get(mandatoryEnv(confLines[i+2])))); break;
        default: throw new Exception("bad config");
    }
    return conf;
}
void main(String[] args){
    try{
        final var serializer = new ByteArraySerializer();
        try(final var producer = new KafkaProducer<byte[],byte[]>(readConf(), serializer, serializer)) {
            System.out.println(producer.send(new ProducerRecord<byte[],byte[]>(args[0], new byte[0])).get().offset());
        }
    } catch(Exception e){
        e.printStackTrace();
        System.exit(1);
    }
}
