
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.net.*;
import java.nio.file.*;

String mandatoryEnv(String key) throws Exception {
    final var v = System.getenv(key);
    if(v == null || v.length() == 0) throw new Exception("no "+key);
    return v;
}

boolean isDebugging(){
    return System.getenv("C4KAFKA_DEBUG") != null;
}

void writeLine(OutputStream writer, String value) throws Exception {
    writer.write((value + "\n").getBytes(StandardCharsets.UTF_8));
    writer.flush();
}

void runTcpServer(int port, HashMap<String, Object> kafkaConf) throws Exception {
    final var threadPool = Executors.newCachedThreadPool();
    final var serializer = new ByteArraySerializer();
    try (
            final var serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
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
                        case "PRODUCE" -> { // Produce mode: send lines to topic
                            writeLine(writer, "OK");
                            String line;
                            while ((line = reader.readLine()) != null) {
                                final var record = new ProducerRecord<byte[], byte[]>(topic, line.getBytes(StandardCharsets.UTF_8));
                                final var future = producer.send(record);
                                writeLine(writer, "ACK " + future.get().offset());
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
        final var conf = new HashMap<String, Object>();
        final var confLine = mandatoryEnv("C4KAFKA_CONFIG");
        final var confLines = confLine.split(confLine.substring(0,1),-1);
        for(var i = 1; i < confLines.length; i+=3) switch(confLines[i]){
            case "C": conf.put(confLines[i+1], confLines[i+2]); break;
            case "E": conf.put(confLines[i+1], Files.readString(Paths.get(mandatoryEnv(confLines[i+2])))); break;
            default: throw new Exception("bad config");
        }
        runTcpServer(Integer.parseInt(args[0]), conf);
    } catch(Exception e){
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
        System.exit(1);
    }
}