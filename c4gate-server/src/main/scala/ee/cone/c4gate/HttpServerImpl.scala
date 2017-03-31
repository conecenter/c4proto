
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.Single
import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.ToAlienWrite
import ee.cone.c4gate.AuthProtocol._
import ee.cone.c4proto._

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean
}

class HttpGetHandler(worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean = {
    if(httpExchange.getRequestMethod != "GET") return false
    val path = httpExchange.getRequestURI.getPath
    val now = System.currentTimeMillis
    val local = worldProvider.createTx()
    val world = TxKey.of(local).world
    val publicationsByPath = By.srcId(classOf[HttpPublication]).of(world)
    publicationsByPath.getOrElse(path,Nil).filter(_.until.forall(now<_)) match {
      case publication :: Nil ⇒
        val headers = httpExchange.getResponseHeaders
        publication.headers.foreach(header⇒headers.add(header.key,header.value))
        val bytes = publication.body.toByteArray
        httpExchange.sendResponseHeaders(200, bytes.length)
        if(bytes.nonEmpty) httpExchange.getResponseBody.write(bytes)
      case _ ⇒
        httpExchange.sendResponseHeaders(404, 0)
    }
    true
  }
}

object AuthOperations {
  private def generateSalt(size: Int): okio.ByteString = {
    val random = new SecureRandom()
    val salt = new Array[Byte](size)
    random.nextBytes(salt)
    toImmutable(salt)
  }
  private def toImmutable(data: Array[Byte]) = okio.ByteString.of(data,0,data.length)
  private def pbkdf2(password: String, template: SecureHash): SecureHash = {
    val spec = new PBEKeySpec(password.toCharArray, template.salt.toByteArray, template.iterations, template.hashSizeInBytes * 8)
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    template.copy(hash=toImmutable(skf.generateSecret(spec).getEncoded))
  }
  def createHash(password: String): SecureHash =
    pbkdf2(password, SecureHash(64000, 18, generateSalt(24), okio.ByteString.EMPTY))
  def verify(password: String, correctHash: SecureHash): Boolean =
    correctHash == pbkdf2(password, correctHash)
}

class HttpPostHandler(qMessages: QMessages, worldProvider: WorldProvider) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean = {
    if(httpExchange.getRequestMethod != "POST") return false
    val headers = httpExchange.getRequestHeaders.asScala
      .flatMap{ case(k,l)⇒l.asScala.map(v⇒Header(k,v)) }.toList
    val headerMap = headers.map(h⇒h.key→h.value).toMap
    val local = worldProvider.createTx()
    val requestId = UUID.randomUUID.toString
    val path = httpExchange.getRequestURI.getPath
    val buffer = (new okio.Buffer).readFrom(httpExchange.getRequestBody)
    val post: okio.ByteString ⇒ HttpPost =
      HttpPost(requestId, path, headers, _, System.currentTimeMillis)

    val requests: Seq[Product] = headerMap.get("X-r-auth") match {
      case None ⇒ List(post(buffer.readByteString()))
      case Some("change") ⇒
        val Array(password,again) = buffer.readUtf8().split("\n")
        if(password!=again) throw new Exception("passwords do not match")
        val hash = Option(AuthOperations.createHash(password))
        List(
          PasswordChangeRequest(requestId, hash),
          post(okio.ByteString.encodeUtf8(requestId))
        )
      case Some("check") ⇒
        val Array(userName,password) = buffer.readUtf8().split("\n")
        val world = TxKey.of(local).world
        val hashesByUser = By.srcId(classOf[PasswordHashOfUser]).of(world)
        val hash = Single.option(hashesByUser.getOrElse(userName,Nil)).map(_.hash.get)
        val endTime = System.currentTimeMillis() + 1000
        val hashOK = hash.exists(pass⇒AuthOperations.verify(password,pass))
        Thread.sleep(Math.max(0,endTime-System.currentTimeMillis()))
        val currentSessionKey = headerMap("X-r-session")
        val newId = UUID.randomUUID.toString
        post(okio.ByteString.EMPTY) ::
        (if(hashOK) List(
          AuthenticatedSession(newId, userName),
          ToAlienWrite(newId,currentSessionKey,"signedIn",newId,0)
        ) else Nil)
      case _ ⇒ throw new Exception("unsupported auth action")
    }
    LEvent.add(requests.flatMap(LEvent.update)).andThen(qMessages.send)(local)
    httpExchange.sendResponseHeaders(200, 0)
    true
  }
}

class ReqHandler(handlers: List[RHttpHandler]) extends HttpHandler {
  def handle(httpExchange: HttpExchange) = Trace{ try {
    handlers.find(_.handle(httpExchange))
  } finally httpExchange.close() }
}

class RHttpServer(port: Int, handler: HttpHandler) extends Executable {
  def run(ctx: ExecutionContext): Unit = {
    val server: HttpServer = HttpServer.create(new InetSocketAddress(port),0)
    ctx.onShutdown("HttpServer",()⇒server.stop(Int.MaxValue))
    server.setExecutor(ctx.executors)
    server.createContext("/", handler)
    server.start()
  }
}

class WorldProviderImpl(
  reducer: Reducer,
  worldFuture: CompletableFuture[()⇒World] = new CompletableFuture()
) extends WorldProvider with Observer {
  def createTx(): World = worldFuture.get.apply()
  def activate(ctx: ObserverContext): Seq[Observer] = {
    worldFuture.complete(()⇒reducer.createTx(ctx.getWorld())(Map()))
    Nil
  }
}

trait InternetForwarderApp extends ProtocolsApp with InitialObserversApp {
  def qReducer: Reducer
  lazy val worldProvider: WorldProvider with Observer = new WorldProviderImpl(qReducer)
  override def protocols: List[Protocol] = AuthProtocol :: HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] = worldProvider :: super.initialObservers
}

trait HttpServerApp extends ToStartApp {
  def config: Config
  def worldProvider: WorldProvider
  def httpHandlers: List[RHttpHandler]
  private lazy val httpPort = config.get("C4HTTP_PORT").toInt
  lazy val httpServer: Executable =
    new RHttpServer(httpPort, new ReqHandler(new HttpGetHandler(worldProvider) :: httpHandlers))

  override def toStart: List[Executable] = httpServer :: super.toStart
}
