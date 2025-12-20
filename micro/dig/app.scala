import java.io.{BufferedReader, InputStream, InputStreamReader, ByteArrayInputStream}
import java.util.concurrent.{BlockingQueue, Executors, LinkedBlockingQueue}
import java.util.zip.GZIPInputStream
import java.lang.Thread
import java.lang.Thread.{UncaughtExceptionHandler, currentThread, sleep, startVirtualThread}
import java.lang.ProcessBuilder
import java.lang.System.getenv
import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpRequest.BodyPublishers
import java.nio.file.Files
import java.nio.file.Files.{readAllBytes, readString}
import java.nio.file.Paths
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.time.Duration.ofSeconds
import java.util.{Base64, Locale}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.sun.net.httpserver.HttpServer
import scala.util.{Try, Using}
import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.{Duration, SECONDS}

//// ---- util

class ErrHandler(inner: UncaughtExceptionHandler) extends UncaughtExceptionHandler:
  def uncaughtException(thread: Thread, throwable: Throwable): Unit =
    try inner.uncaughtException(thread,throwable) finally System.exit(1)

def fatalVT(f:()=>Unit): Unit = startVirtualThread: () =>
  currentThread.setUncaughtExceptionHandler(ErrHandler(currentThread.getUncaughtExceptionHandler))
  f()

type StartVT = (()=>Unit)=>Unit

def vtWrapOutputStream(proc: Process, out: Int=>Unit, start: StartVT): String=>Unit =
  val inQ = new LinkedBlockingQueue[Option[String]]
  start: () =>
    val os = proc.getOutputStream
    try
      for line <- Iterator.continually(inQ.take()).takeWhile(_.nonEmpty).flatten do
        os.write((line + "\n").getBytes(UTF_8))
        os.flush()
    finally os.close()
  start: () =>
    val st = proc.waitFor()
    inQ.put(None)
    out(st)
  (line: String) => inQ.put(Option(line))

def lineIterator(is: InputStream): Iterator[String] =
  val r = new BufferedReader(new InputStreamReader(is, UTF_8))
  Iterator.continually(Option(r.readLine())).takeWhile(_.nonEmpty).flatten // todo replace by ws? this pipe read consumes native thread anyway

def vtWrapInputStream(is: InputStream, out: String=>Unit, start: StartVT): Unit =
  start(() => for line <- lineIterator(is) do out(line))

def env(k: String): String = Option(getenv(k)).getOrElse(throw Exception(s"missing $k"))

def log(value: String): Unit = System.err.println(value)

//// ---- s3 util

final class S3Man:
  private val confDir = Paths.get(env("C4S3_CONF_DIR"))
  private val confKey = readString(confDir.resolve("key"))
  private val confAddress = readString(confDir.resolve("address"))
  private val confSecret = readAllBytes(confDir.resolve("secret"))
  private val client = HttpClient.newBuilder().connectTimeout(ofSeconds(5)).build()
  def sign(canonicalIn: String): String =
    val algorithm = "HmacSHA1"
    val mac = Mac.getInstance(algorithm)
    mac.init(new SecretKeySpec(confSecret, algorithm))
    val signature = new String(Base64.getEncoder.encode(mac.doFinal(canonicalIn.getBytes(UTF_8))),UTF_8)
    s"AWS $confKey:$signature"
  def send(resource: String, method: String, contentType: String, builder: HttpRequest.Builder): Option[Array[Byte]] =
    val resourceWOSearch = resource.takeWhile(_!='?')
    val date = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
      .withZone(ZoneId.of("GMT")).format(Instant.now())
    val authorization = sign(s"$method\n\n$contentType\n$date\n$resourceWOSearch")
    val uri = new URI(s"$confAddress$resource")
    val req = builder.uri(uri).header("Date",date).header("Authorization",authorization).timeout(ofSeconds(30)).build()
    log(s"starting ${req.method} $resource")
    val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
    val status = resp.statusCode()
    log(s"status $status")
    if status >= 200 && status < 300 then Option(resp.body()) else None
  def put(resource: String, body: Array[Byte]): Unit =
    val contentType = "application/octet-stream"
    val builder = HttpRequest.newBuilder().header("Content-Type",contentType).PUT(BodyPublishers.ofByteArray(body))
    if send(resource, "PUT", contentType, builder).isEmpty then throw new Exception(s"put ($resource)")

//// ---- app logic

sealed trait PodManMsg
case class LogMsg(line: String) extends PodManMsg
case class ActivatePodsMsg() extends PodManMsg
case class ActivatePodMsg(name: String) extends PodManMsg
case class PodOutMsg(from: String, line: String) extends PodManMsg
case class PodFinMsg(from: String, code: Int) extends PodManMsg
case class DelInPodMsg(podName: String, fileName: String) extends PodManMsg
class ErrMsg(val err: Throwable) extends PodManMsg
case class MetricsSetMsg(pod: String, k: String, v: String) extends PodManMsg
class MetricsReq(val promise: Promise[MetricsResp]) extends PodManMsg
case class MetricsResp(status: Int, content: String)
class MainState(
  val podSend: Map[String,String=>Unit], val podMetrics: Map[(String,String),String], val errCount: Map[String,Long],
)

def getKC: Seq[String] = Seq("kubectl", "--kubeconfig", env("C4KUBECONFIG"), "--context", env("C4DIG_KUBE_CONTEXT"))

def createPod(q: BlockingQueue[PodManMsg], start: StartVT, nm: String): String=>Unit =
  val agentCodePath = env("C4UP_PATH").split(":").map(d=>Paths.get(d).resolve("agent.py")).filter(Files.exists(_)).head
  val agentCode = readString(agentCodePath) + "\nmain()"
  val proc = new ProcessBuilder(getKC ++ Seq("exec", "-i", nm, "--", "python3", "-u", "-c", agentCode) *).start()
  val send = vtWrapOutputStream(proc, code => q.put(PodFinMsg(nm, code)), start)
  vtWrapInputStream(proc.getInputStream, l => q.put(PodOutMsg(nm, l)), start)
  vtWrapInputStream(proc.getErrorStream, l => q.put(LogMsg(s"$nm $l")), start)
  val installData = new String(Base64.getEncoder.encode(readAllBytes(Paths.get("/c4/c4dig.tar.gz"))), UTF_8)
  send(ujson.write(Seq("install", installData)))
  send

def activatePods(q: BlockingQueue[PodManMsg], start: StartVT): Unit =
  val re = env("C4DIG_POD_RE")
  val prefix = "pod/"
  def getName(l: String) = if l.startsWith(prefix) then Option(l.substring(prefix.length)) else None
  val proc = new ProcessBuilder(Seq("timeout", "5") ++ getKC ++ Seq("get", "pods", "-o", "name") *).start()
  vtWrapInputStream(proc.getInputStream, l => for n <- getName(l) if re.r.matches(n) do q.put(ActivatePodMsg(n)), start)
  vtWrapInputStream(proc.getErrorStream, l => q.put(LogMsg(s"manager $l")), start)

def startMetricsServer(q: BlockingQueue[PodManMsg]): Unit =
  val server = HttpServer.create(new InetSocketAddress(env("C4METRICS_PORT").toInt), 0)
  server.createContext("/metrics", exchange =>
    val respP = Promise[MetricsResp]()
    q.put(MetricsReq(respP))
    val resp = Try(Await.result(respP.future, Duration(2,SECONDS))).getOrElse(MetricsResp(500,""))
    val bytes = resp.content.getBytes(UTF_8)
    exchange.sendResponseHeaders(resp.status, bytes.length)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  )
  server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
  server.start()

def startTicking(q: BlockingQueue[PodManMsg]): Unit = fatalVT: () =>
  for _ <- Iterator.continually(()) do
    q.put(ActivatePodsMsg())
    sleep(45000)

def increment(errCount: Map[String, Long], key: String): Map[String, Long] =
  errCount.updated(key, errCount.getOrElse(key, 0L)+1L)

def notifyErr(was: MainState, e: Throwable): MainState =
  log(s"ERROR ${e.getMessage}")
  e.printStackTrace()
  MainState(was.podSend, was.podMetrics, increment(was.errCount, "handle"))

def handlePodOutMsg(q: BlockingQueue[PodManMsg], s3: S3Man, from: String, line: String): Unit =
  val msg = ujson.read(line)
  msg("tp").str match
    case "file" =>
      val bucket = env("C4DIG_BUCKET")
      val FN = """([\w\-.]+)""".r
      val FN(fileName) = msg("name").str: @unchecked
      val compressed = Base64.getDecoder().decode(msg("data").str)
      val data = Using(new GZIPInputStream(new ByteArrayInputStream(compressed)))(_.readAllBytes()).get
      s3.put(s"/$bucket/pod/$from/$fileName", data)
      q.put(DelInPodMsg(from, fileName))
    case "metrics" =>
      val Key = """([a-z_]+)""".r
      val Val = """(\d+)""".r
      val Key(k) = msg("key").str: @unchecked
      val Val(v) = msg("value").str: @unchecked
      q.put(MetricsSetMsg(from, k, v))

@main def main(): Unit =
  println("starting...")
  val q = new LinkedBlockingQueue[PodManMsg]
  startTicking(q)
  startMetricsServer(q)
  val s3 = S3Man()
  val start: StartVT = f => fatalVT(() => Try(f()).recover(e => q.put(ErrMsg(e))))
  def handle(was: MainState, msg: PodManMsg) = msg match
    case LogMsg(line) =>
      log(line)
      was
    case ActivatePodsMsg() =>
      activatePods(q, start)
      was
    case ActivatePodMsg(name) =>
      val send = was.podSend.getOrElse(name, createPod(q, start, name))
      send("""["st"]""")
      MainState(was.podSend.updated(name, send), was.podMetrics, was.errCount)
    case PodOutMsg(from, line) =>
      start(() => handlePodOutMsg(q, s3, from, line))
      was
    case MetricsSetMsg(p, k, v) => MainState(was.podSend, was.podMetrics.updated((p, k), v), was.errCount)
    case PodFinMsg(nm, code) =>
      log(s"$nm exited with $code")
      val willMetrics = was.podMetrics.filter{ case ((pod, _), _) => pod != nm }
      MainState(was.podSend.removed(nm), willMetrics, increment(was.errCount, "pod_fin"))
    case DelInPodMsg(podName, fileName) =>
      for send <- was.podSend.get(podName) do send(ujson.write(Seq("rm", fileName)))
      was
    case msg: ErrMsg => notifyErr(was, msg.err)
    case req: MetricsReq =>
      val lines = for ((podName,k),v) <- was.podMetrics yield s"c4dig:$k{pod=\"$podName\"} $v\n"
      val errCountLines = for k <- Seq("handle","pod_fin") yield s"c4dig_err_count:$k ${was.errCount.getOrElse(k, 0)}\n"
      req.promise.success(MetricsResp(200, (lines++errCountLines).toSeq.sorted.mkString))
      was
  Iterator.continually(q.take()).foldLeft(MainState(Map.empty, Map.empty, Map.empty)) : (was, msg) =>
    Try(handle(was, msg)).recover(notifyErr(was, _)).get
///