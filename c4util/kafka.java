
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Properties;

Properties loadConf(String path) throws Exception {
    try(final var input = new FileInputStream(path)) {
        final var conf = new Properties();
        conf.load(input);
        return conf;
    }
}

void respond(HttpExchange exchange, int code, String content) throws IOException {
    final var data = content.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain");
    exchange.sendResponseHeaders(code, data.length);
    exchange.getResponseBody().write(data);
    exchange.close();
}

long kafkaSendEmpty(KafkaProducer<byte[],byte[]> producer, String topic) throws Exception {
    return producer.send(new ProducerRecord<>(topic, new byte[0])).get().offset();
}

boolean kafkaDelete(AdminClient client, String topic) throws Exception {
    final var exists = client.listTopics().names().get().contains(topic);
    if(exists) client.deleteTopics(Collections.singletonList(topic)).all().get();
    return exists;
}

void main(String[] args){
    try{
        final var port = Integer.parseInt(args[0]);
        final var kafkaConf = loadConf(args[1]);
        final var serializer = new ByteArraySerializer();
        try (
                final var adminClient = AdminClient.create(kafkaConf);
                final var producer = new KafkaProducer<byte[],byte[]>(kafkaConf, serializer, serializer)
        ) {
            final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 2);
            server.createContext("/", exchange -> {
                try {
                    final var method = exchange.getRequestMethod();
                    final var path = exchange.getRequestURI().getPath().split("/");
                    if (!method.equals("POST")) respond(exchange, 405, "");
                    else if(path.length != 3) respond(exchange, 404, "");
                    else switch (path[1]) {
                        case "send" -> respond(exchange, 200, String.valueOf(kafkaSendEmpty(producer, path[2])));
                        case "rm" -> respond(exchange, 200, String.valueOf(kafkaDelete(adminClient, path[2])));
                    }
                } catch (Exception e) {
                    //noinspection CallToPrintStackTrace
                    e.printStackTrace();
                    respond(exchange, 500, "");
                }
            });
            //server.setExecutor(null)
            server.start();
        }
    } catch(Exception e){
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
        System.exit(1);
    }
}
