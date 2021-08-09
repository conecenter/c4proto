
import java.util.HashMap;
import java.util.TreeSet;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Scanner;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.clients.admin.ListOffsetsResult;

class KafkaApp {

    static String mandatoryEnv(String key) throws Exception {
        final var v = System.getenv(key);
        if(v == null || v.length() == 0) throw new Exception("no "+key);
        return v;
    }

    static KafkaFuture<ListOffsetsResult.ListOffsetsResultInfo> listOffsets(
        AdminClient client,
        TopicPartition topicPartition,
        OffsetSpec offsetSpec
    ) {
        final var offsetSpecMap = Collections.singletonMap(topicPartition, offsetSpec);
        final var latestOffsets = client.listOffsets(offsetSpecMap);
        return latestOffsets.partitionResult(topicPartition);
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
            try(final var client = AdminClient.create(conf)){
                try {
                    switch (args[0]) {
                        case "topics" -> {
                            final var topicNames = new TreeSet<String>(client.listTopics().names().get());
                            final var topicConfigResources = topicNames.stream()
                                    .map(topicName -> new ConfigResource(ConfigResource.Type.TOPIC, topicName))
                                    .collect(Collectors.toUnmodifiableList());
                            final var topicConfigDescriptions = client.describeConfigs(topicConfigResources).all().get();
                            topicConfigResources.forEach(topicConfigResource -> {
                                final var topicName = topicConfigResource.name();
                                final var retention = topicConfigDescriptions
                                        .get(topicConfigResource)
                                        .get(TopicConfig.RETENTION_MS_CONFIG).value();
                                System.out.println("topic " + topicName + " retention " + retention);
                            });
                        }
                        case "offsets" -> {
                            final var hours = Long.parseLong(args[1]);
                            final var now = System.currentTimeMillis();
                            final var timestamp = now - hours * 60 * 60 * 1000;
                            System.out.println("now "+now+" timestamp "+timestamp);
                            final var topicNames = new TreeSet<String>(client.listTopics().names().get());
                            final var topicDescriptions = client.describeTopics(topicNames).all().get();
                            final var topicPartitions = topicNames.stream().flatMap(topicName ->
                                    topicDescriptions.get(topicName).partitions().stream()
                                            .map(info -> new TopicPartition(topicName, info.partition()))
                            ).collect(Collectors.toUnmodifiableList());
                            for (var topicPartition : topicPartitions) {
                                try {
                                    final var earliestF = listOffsets(client, topicPartition, OffsetSpec.earliest());
                                    final var latestF = listOffsets(client, topicPartition, OffsetSpec.latest());
                                    final var earliest = earliestF.get(1, TimeUnit.SECONDS);
                                    final var latest = latestF.get(1, TimeUnit.SECONDS);
                                    final var forTimestampF = listOffsets(client, topicPartition, OffsetSpec.forTimestamp(timestamp));
                                    final var forTimestamp = forTimestampF.get(1, TimeUnit.SECONDS);
                                    System.out.println("tp " + topicPartition
                                            + " earliest " + earliest.offset() + " latest " + latest.offset()
                                            + " forTimestamp " + forTimestamp.offset() + " " + forTimestamp.timestamp()
                                    );
                                } catch (TimeoutException e){
                                    System.out.println("tp " + topicPartition + " ... timeout");
                                }
                            }
                        }
                        case "nodes" -> client.describeCluster().nodes().get().forEach(node -> {
                            System.out.println("node " + node.id());
                        });
                        case "sizes" -> {
                            final var nodeId = Integer.parseInt(args[1]);
                            final var nodeIds = Collections.singletonList(nodeId);
                            client.describeLogDirs(nodeIds).descriptions().get(nodeId).get().forEach((path, logDirDescription) -> {
                                System.out.println("path " + path);
                                logDirDescription.replicaInfos().forEach((topicPartition, replicaInfo) -> {
                                    System.out.println("topic " + topicPartition.topic() + " partition " + topicPartition.partition() + " size " + replicaInfo.size());
                                });
                            });
                        }
                        case "topics_rm" -> {
                            final var scanner = new Scanner(System.in);
                            while(scanner.hasNextLine()){
                                final var line = scanner.nextLine();
                                System.out.println("removing "+line);
                                client.deleteTopics(Collections.singletonList(line)).all().get();
                            }
                        }
                    }
                } catch (Exception e){
                    e.printStackTrace(); // print here, outer try(client) can hang on close
                    throw e;
                }
            }
        } catch(Exception e){
            System.exit(1);
        }
    }
}
