
import java.util.HashMap;
import java.util.TreeSet;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;

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
            try(final var client = AdminClient.create(conf)){
                final var topicNames = new TreeSet<String>(client.listTopics().names().get());
                final var topicConfigResources = topicNames.stream().map(topicName->new ConfigResource(ConfigResource.Type.TOPIC, topicName)).collect(Collectors.toUnmodifiableList());
                //final var topicMap = client.describeTopics(topicNames).all().get();
                final var descriptions = client.describeConfigs(topicConfigResources).all().get();


                topicConfigResources.forEach(topicConfigResource->{
                    final var topicName = topicConfigResource.name();
                    System.out.println(topicName);
//                    topicMap.get(topicName).partitions().forEach(partition->{
//                        System.out.println("\t"+partition.partition());
//                    });
                    System.out.println("\tret "+descriptions.get(topicConfigResource).get(TopicConfig.RETENTION_MS_CONFIG).value());

//                    descriptions.get(topicConfigResource).entries().forEach(configEntry->{
//                        System.out.println("\t"+configEntry.name()+" = "+configEntry.value());
//                    });
                });





            }

        } catch(Exception e){
            e.printStackTrace();
            System.exit(1);
        }
    }
}
