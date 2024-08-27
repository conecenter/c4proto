
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.format.DateTimeFormatter;
import java.time.*;
import java.util.*;

String mandatoryEnv(String key) throws Exception {
    final var v = System.getenv(key);
    if(v == null || v.length() == 0) throw new Exception("no "+key);
    return v;
}

void main(String[] args){
    try{
        final var confDir = Paths.get(mandatoryEnv("C4S3_CONF_DIR"));
        final var key = Files.readString(confDir.resolve("key"));
        final var secret = Files.readAllBytes(confDir.resolve("secret"));
        final var date = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.ENGLISH)
                .withZone(ZoneId.of("GMT")).format(Instant.now());
        final var canonicalIn = args[0].replace("[date]", date);
        final var algorithm = "HmacSHA1";
        final var mac = Mac.getInstance(algorithm);
        final var UTF_8 = StandardCharsets.UTF_8;
        mac.init(new SecretKeySpec(secret, algorithm));
        final var signature = new String(Base64.getEncoder().encode(mac.doFinal(canonicalIn.getBytes(UTF_8))),UTF_8);
        System.out.println(STR."Date:\{date}");
        System.out.println(STR."Authorization:AWS \{key}:\{signature}");
    } catch(Exception e){
        e.printStackTrace();
        System.exit(1);
    }
}
