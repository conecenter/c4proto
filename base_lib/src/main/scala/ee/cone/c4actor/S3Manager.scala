package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di.c4

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.concurrent.{CompletionStage, Executors, Flow, ScheduledExecutorService, TimeUnit}
import java.util.{Base64, Locale}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.jdk.FutureConverters._

// fires if a request makes no progress for idleMs:
//   progress = request start, request body chunk taken for sending, response headers, response body chunk;
//   so long but moving transfers are not limited, silent connections are
final class IdleWatchdog(scheduler: ScheduledExecutorService, idleMs: Long) {
  @volatile private var last: Long = System.nanoTime()
  @volatile private var onIdle: Option[()=>Unit] = None
  def touch(): Unit = last = System.nanoTime()
  def start(f: ()=>Unit): Unit = { touch(); onIdle = Option(f); check() }
  def stop(): Unit = onIdle = None // stale scheduled check keeps only this small object
  private def check(): Unit = onIdle.foreach{ f =>
    val left = last + TimeUnit.MILLISECONDS.toNanos(idleMs) - System.nanoTime()
    if(left > 0) scheduler.schedule((()=>check()):Runnable, left, TimeUnit.NANOSECONDS)
    else { stop(); f() }
  }
}

final class TouchingPublisher(inner: HttpRequest.BodyPublisher, touch: ()=>Unit) extends HttpRequest.BodyPublisher {
  def contentLength(): Long = inner.contentLength()
  def subscribe(subscriber: Flow.Subscriber[_ >: ByteBuffer]): Unit = inner.subscribe(new Flow.Subscriber[ByteBuffer]{
    def onSubscribe(subscription: Flow.Subscription): Unit = subscriber.onSubscribe(subscription)
    def onNext(item: ByteBuffer): Unit = { touch(); subscriber.onNext(item) }
    def onError(throwable: Throwable): Unit = subscriber.onError(throwable)
    def onComplete(): Unit = subscriber.onComplete()
  })
}

final class TouchingBodySubscriber[T](inner: HttpResponse.BodySubscriber[T], touch: ()=>Unit) extends HttpResponse.BodySubscriber[T] {
  def getBody: CompletionStage[T] = inner.getBody
  def onSubscribe(subscription: Flow.Subscription): Unit = inner.onSubscribe(subscription)
  def onNext(item: java.util.List[ByteBuffer]): Unit = { touch(); inner.onNext(item) }
  def onError(throwable: Throwable): Unit = inner.onError(throwable)
  def onComplete(): Unit = inner.onComplete()
}

@c4("S3ManagerApp") final class S3ManagerImpl(
  config: Config, httpClientProvider: HttpClientProvider, execution: Execution
)(
  confDir: Path = Paths.get(config.get("C4S3_CONF_DIR")),
  idleTimeoutMs: Long = 10000L,
  watchdogScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor{ (r: Runnable) =>
    val t = new Thread(r, "s3-watchdog")
    t.setDaemon(true)
    t
  },
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

  private def newWatchdog(): IdleWatchdog = new IdleWatchdog(watchdogScheduler, idleTimeoutMs)

  def sendInner(resource: String, date: String, authorization: String, builder: HttpRequest.Builder, watchdog: IdleWatchdog)(implicit ec: ExecutionContext): Future[Option[Array[Byte]]] = {
    val client = Await.result(httpClientProvider.get, Duration.Inf)
    val uri = s"${conf("address")}$resource"
    val req = builder.uri(new URI(uri))
      .header("Date",date)
      .header("Authorization",authorization)
      .build()
    logger.debug(s"starting ${req.method} $uri")
    val bodyHandler: HttpResponse.BodyHandler[Array[Byte]] = _ => {
      watchdog.touch()
      new TouchingBodySubscriber(HttpResponse.BodySubscribers.ofByteArray(), watchdog.touch)
    }
    val cf = client.sendAsync(req,bodyHandler)
    val respPromise = Promise[HttpResponse[Array[Byte]]]()
    watchdog.start{ () =>
      logger.warn(s"no progress for $idleTimeoutMs ms: ${req.method} $uri")
      respPromise.tryFailure(new HttpTimeoutException(s"no progress for $idleTimeoutMs ms: ${req.method} $uri"))
      cf.cancel(true) // cancels the exchange, closing the connection, so it is not returned to the pool
    }
    respPromise.completeWith(cf.asScala.andThen{ case _ => watchdog.stop() })
    for(resp <- respPromise.future) yield {
      //println(req.method(),resp.statusCode(),resp.statusCode(),new String(resp.body(),StandardCharsets.UTF_8))
      val status = resp.statusCode()
      logger.debug(s"status $status")
      if(status>=200 && status<300) Option(resp.body()) else {
        logger.debug(s"err body: ${new String(resp.body(),UTF_8)}")
        None
      }
    }
  }

  def send(resourceWithPrefix: String, method: String, contentType: String, builder: HttpRequest.Builder, watchdog: IdleWatchdog = newWatchdog())(implicit ec: ExecutionContext): Future[Option[Array[Byte]]] = {
    val date = getDateStr
    val resourceWOSearch = resourceWithPrefix.takeWhile(_!='?')
    sendInner(resourceWithPrefix, date, sign(s"$method\n\n$contentType\n$date\n$resourceWOSearch"), builder, watchdog)
  }

  def putInner(resourceWithPrefix: String, body: Array[Byte]): Boolean = {
    val contentType = "application/octet-stream"
    val watchdog = newWatchdog()
    val builder = HttpRequest.newBuilder()
      .header("Content-Type",contentType)
      .PUT(new TouchingPublisher(HttpRequest.BodyPublishers.ofByteArray(body), watchdog.touch))
    execution.aWait{ implicit ec =>
      send(resourceWithPrefix, "PUT", contentType, builder, watchdog).map(_.nonEmpty)
    }
  }
  def put(resourceWithPrefix: String, body: Array[Byte]): Unit =
    if(!putInner(resourceWithPrefix, body)){
      val Array("",bucket,_) = resourceWithPrefix.split('/')
      if(!putInner(s"/$bucket", Array.empty))
        throw new Exception(s"put ($resourceWithPrefix)")
      Thread.sleep(3000)
      if(!putInner(resourceWithPrefix, body))
        throw new Exception(s"put ($resourceWithPrefix)")
    }

  def delete(resourceWithPrefix: String)(implicit ec: ExecutionContext): Future[Boolean] =
    send(resourceWithPrefix, "DELETE", "", HttpRequest.newBuilder().DELETE())
      .map(_.nonEmpty)

  def get(resourceWithPrefix: String)(implicit ec: ExecutionContext): Future[Option[Array[Byte]]] =
    send(resourceWithPrefix, "GET", "", HttpRequest.newBuilder().GET())

  def join(txLogName: TxLogName, resource: String): String = s"/${txLogName.value}.$resource"
  def put(txLogName: TxLogName, resource: String, body: Array[Byte]): Unit =
    put(join(txLogName, resource), body)
  def delete(txLogName: TxLogName, resource: String)(implicit ec: ExecutionContext): Future[Boolean] =
    delete(join(txLogName, resource))(ec)
  def get(txLogName: TxLogName, resource: String)(implicit ec: ExecutionContext): Future[Option[Array[Byte]]] =
    get(join(txLogName, resource))(ec)
}