
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.net.*;

Properties loadConf(String path) throws Exception {
    try(final var input = new FileInputStream(path)) {
        final var conf = new Properties();
        conf.load(input);
        return conf;
    }
}

boolean isDebugging(){
    return System.getenv("C4KAFKA_DEBUG") != null;
}

void writeLine(OutputStream writer, String value) throws Exception {
    writer.write((value + "\n").getBytes(StandardCharsets.UTF_8));
    writer.flush();
}

void runTcpServer(int port, Properties kafkaConf) throws Exception {
    final var threadPool = Executors.newCachedThreadPool();
    final var serializer = new ByteArraySerializer();
    final var deserializer = new ByteArrayDeserializer();
    try (
            final var serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
            final var adminClient = AdminClient.create(kafkaConf);
            final var producer = new KafkaProducer<byte[], byte[]>(kafkaConf, serializer, serializer)
    ) {
        while (true) {
            final var socket = serverSocket.accept();
            threadPool.submit(() -> {
                if(isDebugging()) System.err.println("Client connected: " + socket.getRemoteSocketAddress());
                try (
                    socket;
                    final var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    final var writer = socket.getOutputStream()
                ) {
                    final var args = reader.readLine().split(" ");
                    final var topic = args[1];
                    switch (args[0]) {
                        case "DELETE" -> {
                            final var exists = adminClient.listTopics().names().get().contains(topic);
                            if(exists) adminClient.deleteTopics(Collections.singletonList(topic)).all().get();
                            writeLine(writer, "EXISTED "+exists);
                        }
                        case "PRODUCE" -> { // Produce mode: send lines to topic
                            writeLine(writer, "OK");
                            String line;
                            while ((line = reader.readLine()) != null) {
                                final var record = new ProducerRecord<byte[], byte[]>(topic, line.getBytes(StandardCharsets.UTF_8));
                                final var future = producer.send(record);
                                writeLine(writer, "ACK " + future.get().offset());
                            }
                        }
                        case "CONSUME" -> {
                            try (var consumer = new KafkaConsumer<byte[], byte[]>(kafkaConf, deserializer, deserializer)) {
                                final var partition = new TopicPartition(topic, 0);
                                consumer.assign(List.of(partition));
                                final var beginning = consumer.beginningOffsets(List.of(partition)).get(partition);
                                final var end = consumer.endOffsets(List.of(partition)).get(partition);
                                writeLine(writer, "BEGINNING " + beginning + " END " + end);
                                consumer.seek(partition, java.lang.Long.parseLong(reader.readLine()));
                                while (true) {
                                    final var records = consumer.poll(Duration.ofMillis(500));
                                    for (var record : records) {
                                        writer.write(record.value());
                                        writer.write('\n');
                                    }
                                    writer.flush();
                                }
                            }
                        }
                        default -> {
                            writer.write("Unknown mode\n".getBytes(StandardCharsets.UTF_8));
                            writer.flush();
                        }
                    }
                } catch (Exception e) {
                    if(isDebugging()) {
                        System.err.println("Client disconnected: " + socket.getRemoteSocketAddress());
                        e.printStackTrace();
                    }
                }
            });
        }
    }
}

void main(String[] args){
    try{
        runTcpServer(Integer.parseInt(args[0]), loadConf(args[1]));
    } catch(Exception e){
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
        System.exit(1);
    }
}