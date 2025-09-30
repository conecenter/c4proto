
import java.util.HashMap;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.OffsetSpec;

String mandatoryEnv(String key) throws Exception {
    final var v = System.getenv(key);
    if(v == null || v.length() == 0) throw new Exception("no "+key);
    return v;
}

record Resp(int status, String content){}

Resp requestMetrics(AdminClient admin){
    try {
        final var topics = admin.listTopics().names().get();
        final var descs = admin.describeTopics(topics).all().get();
        final var req = new HashMap<TopicPartition, OffsetSpec>();
        for (var td : descs.values()) for (var p : td.partitions())
            req.put(new TopicPartition(td.name(), p.partition()), OffsetSpec.latest());
        final var results = admin.listOffsets(req).all().get();
        final var resp = new StringBuilder();
        for (var e : results.entrySet())
            resp.append("kafka_topic_partition_current_offset{topic=\"").append(e.getKey().topic())
                    .append("\",partition=\"").append(e.getKey().partition()).append("\"} ")
                    .append(e.getValue().offset()).append("\n");
        return new Resp(200, resp.toString());
    } catch(Exception e){
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
        return new Resp(500, "");
    }
}

void runServer(int port, HashMap<String, Object> kafkaConf) throws Exception {
    try (
        final var admin = AdminClient.create(kafkaConf)
    ){
        final var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> {
            final var resp = requestMetrics(admin);
            final var bytes = resp.content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(resp.status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Thread.sleep(Long.MAX_VALUE);
    }
}

void main(String[] args){
    try{
        final var conf = new HashMap<String, Object>();
        final var confLine = mandatoryEnv("C4KAFKA_CONFIG");
        final var confLines = confLine.split(confLine.substring(0,1),-1);
        for(var i = 1; i < confLines.length; i+=3) switch(confLines[i]){
            case "C": conf.put(confLines[i+1], confLines[i+2]); break;
            case "E": conf.put(confLines[i+1], Files.readString(Paths.get(mandatoryEnv(confLines[i+2])))); break;
            default: throw new Exception("bad config");
        }
        runServer(Integer.parseInt(mandatoryEnv("C4KAFKA_METRICS_PORT")), conf);
    } catch(Exception e){
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
        System.exit(1);
    }
}
