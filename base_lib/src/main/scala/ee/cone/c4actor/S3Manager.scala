package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di.c4

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.{Base64, Locale}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.jdk.FutureConverters._

@c4("S3ManagerApp") final class S3ManagerImpl(
  config: Config, httpClientProvider: HttpClientProvider, execution: Execution
)(
  confDir: Path = Paths.get(config.get("C4S3_CONF_DIR")),
) extends S3Manager with LazyLogging {
  def confBytes(key: String): Array[Byte] = Files.readAllBytes(confDir.resolve(key))
  def conf(key: String): String = new String(confBytes(key),UTF_8)

  def getDateStr: String =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.ENGLISH)
      .withZone(ZoneId.of("GMT")).format(Instant.now())

  def sign(canonicalIn: String): String = {
    val algorithm = "HmacSHA1"
    val mac = Mac.getInstance(algorithm)
    mac.init(new SecretKeySpec(confBytes("secret"), algorithm))
    val signature = new String(Base64.getEncoder.encode(mac.doFinal(canonicalIn.getBytes(UTF_8))),UTF_8)
    s"AWS ${conf("key")}:$signature"
  }

  def sendInner(resource: String, date: String, authorization: String, builder: HttpRequest.Builder)(implicit ec: ExecutionContext): Future[Option[Array[Byte]]] = {
    val client = Await.result(httpClientProvider.get, Duration.Inf)
    val uri = s"${conf("address")}$resource"
    val req = builder.uri(new URI(uri))
      .header("Date",date)
      .header("Authorization",authorization)
      .build()
    logger.debug(s"starting ${req.method} $uri")
    val bodyHandler = HttpResponse.BodyHandlers.ofByteArray()
    for(resp <- client.sendAsync(req,bodyHandler).asScala) yield {
      //println(req.method(),resp.statusCode(),resp.statusCode(),new String(resp.body(),StandardCharsets.UTF_8))
      val status = resp.statusCode()
      logger.debug(s"status $status")
      if(status>=200 && status<300) Option(resp.body()) else {
        logger.debug(s"err body: ${new String(resp.body(),UTF_8)}")
        None
      }
    }
  }

  def send(txLogName: TxLogName, resource: String, method: String, contentType: String, builder: HttpRequest.Builder)(implicit ec: ExecutionContext): Future[Option[Array[Byte]]] = {
    val resourceWithPrefix = s"/${txLogName.value}.$resource"
    val date = getDateStr
    val resourceWOSearch = resourceWithPrefix.takeWhile(_!='?')
    sendInner(resourceWithPrefix, date, sign(s"$method\n\n$contentType\n$date\n$resourceWOSearch"), builder)
  }

  def putInner(txLogName: TxLogName, resource: String, body: Array[Byte]): Boolean = {
    val contentType = "application/octet-stream"
    val builder = HttpRequest.newBuilder()
      .header("Content-Type",contentType)
      .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
    execution.aWait{ implicit ec =>
      send(txLogName, resource, "PUT", contentType, builder).map(_.nonEmpty)
    }
  }
  def put(txLogName: TxLogName, resource: String, body: Array[Byte]): Unit =
    if(!putInner(txLogName, resource, body)){
      val Array(bucket,_) = resource.split('/')
      if(!putInner(txLogName, bucket, Array.empty))
        throw new Exception(s"put ($resource)")
      Thread.sleep(3000)
      if(!putInner(txLogName, resource, body))
        throw new Exception(s"put ($resource)")
    }

  def delete(txLogName: TxLogName, resource: String)(implicit ec: ExecutionContext): Future[Boolean] =
    send(txLogName, resource, "DELETE", "", HttpRequest.newBuilder().DELETE())
      .map(_.nonEmpty)

  def get(txLogName: TxLogName, resource: String)(implicit ec: ExecutionContext): Future[Option[Array[Byte]]] =
    send(txLogName, resource, "GET", "", HttpRequest.newBuilder().GET())
}