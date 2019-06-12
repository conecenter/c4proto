
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CompletableFuture, Executors, TimeUnit}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.{PostConsumer, ToAlienWrite}
import ee.cone.c4gate.AuthProtocol._
import ee.cone.c4proto._

import scala.collection.immutable.Seq
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

class HttpGetPublicationHandler(worldProvider: WorldProvider) extends RHttpHandler with LazyLogging {
  def handle(httpExchange: HttpExchange, reqHeaders: List[N_Header]): Boolean = {
    if(httpExchange.getRequestMethod != "GET") return false
    val path = httpExchange.getRequestURI.getPath
    val now = System.currentTimeMillis
    val local = worldProvider.createTx()
    val publicationsByPath = ByPK(classOf[S_HttpPublication]).of(local)
    publicationsByPath.get(path).filter(_.until.forall(now<_)) match {
      case Some(publication) ⇒
        val cTag = reqHeaders.find(_.key=="If-none-match").map(_.value)
        val sTag = publication.headers.find(_.key=="ETag").map(_.value)
        logger.debug(s"$reqHeaders")
        logger.debug(s"$cTag $sTag")
        (cTag,sTag) match {
          case (Some(a),Some(b)) if a == b ⇒
            httpExchange.sendResponseHeaders(304, 0)
          case _ ⇒
            val headers = httpExchange.getResponseHeaders
            publication.headers.foreach(header⇒headers.add(header.key,header.value))
            val bytes = publication.body.toByteArray
            httpExchange.sendResponseHeaders(200, bytes.length)
            if(bytes.nonEmpty) httpExchange.getResponseBody.write(bytes)
        }
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
    ToByteString(salt)
  }
  private def pbkdf2(password: String, template: N_SecureHash): N_SecureHash = {
    val spec = new PBEKeySpec(password.toCharArray, template.salt.toByteArray, template.iterations, template.hashSizeInBytes * 8)
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    template.copy(hash=ToByteString(skf.generateSecret(spec).getEncoded))
  }
  def createHash(password: String): N_SecureHash =
    pbkdf2(password, N_SecureHash(64000, 18, generateSalt(24), okio.ByteString.EMPTY))
  def verify(password: String, correctHash: N_SecureHash): Boolean =
    correctHash == pbkdf2(password, correctHash)
}

class HttpPostHandler(qMessages: QMessages, worldProvider: WorldProvider) extends RHttpHandler with LazyLogging {
  def handle(httpExchange: HttpExchange, headers: List[N_Header]): Boolean = {
    if(httpExchange.getRequestMethod != "POST") return false
    val headerMap = headers.map(h⇒h.key→h.value).toMap
    val local = worldProvider.createTx()
    val requestId = UUID.randomUUID.toString
    val path = httpExchange.getRequestURI.getPath
    val buffer = (new okio.Buffer).readFrom(httpExchange.getRequestBody)
    val post: okio.ByteString ⇒ S_HttpPost =
      S_HttpPost(requestId, path, headers, _, System.currentTimeMillis)

    val requests: List[Product] = headerMap.get("X-r-auth") match {
      case None ⇒ List(post(buffer.readByteString()))
      case Some("change") ⇒
        val Array(password,again) = buffer.readUtf8().split("\n")
        if(password!=again) throw new Exception("passwords do not match")
        val hash = Option(AuthOperations.createHash(password))
        List(
          S_PasswordChangeRequest(requestId, hash),
          post(okio.ByteString.encodeUtf8(requestId))
        )
      case Some("check") ⇒
        val Array(userName,password) = buffer.readUtf8().split("\n")
        val hashesByUser = ByPK(classOf[C_PasswordHashOfUser]).of(local)
        val hash = hashesByUser.get(userName).map(_.hash.get)
        val endTime = System.currentTimeMillis() + 1000
        val hashOK = hash.exists(pass⇒AuthOperations.verify(password,pass))
        Thread.sleep(Math.max(0,endTime-System.currentTimeMillis()))
        val currentSessionKey = headerMap("X-r-session")
        val newId = UUID.randomUUID.toString
        if(hashOK) List(
          post(ToByteString(newId)),
          U_AuthenticatedSession(newId, userName, Instant.now.plusSeconds(20).getEpochSecond),
          ToAlienWrite(newId,currentSessionKey,"signedIn",newId,0)
        ) else List(
          post(okio.ByteString.EMPTY)
        )
      case _ ⇒ throw new Exception("unsupported auth action")
    }
    TxAdd(requests.flatMap(LEvent.update)).andThen{ nLocal ⇒
      if(ByPK(classOf[HttpPostAllow]).of(nLocal).contains(requestId)){
        qMessages.send(nLocal)
        httpExchange.sendResponseHeaders(200, 0)
        //logger.debug(s"200 $path $headers")
      } else {
        logger.warn(path)
        httpExchange.sendResponseHeaders(429, 0) //Too Many Requests
        //logger.debug(s"429 $path $headers")
      }
    }(local)
    true
  }
}

class ReqHandler(handlers: List[RHttpHandler]) extends HttpHandler {
  def handle(httpExchange: HttpExchange) =
    Trace{ FinallyClose[HttpExchange,Unit](_.close())(httpExchange) { ex ⇒
      val headers: List[N_Header] = httpExchange.getRequestHeaders.asScala
          .flatMap{ case(k,l)⇒l.asScala.map(v⇒N_Header(k,v)) }.toList
      handlers.find(_.handle(ex,headers))
    } }
}

class RHttpServer(port: Int, handler: HttpHandler, execution: Execution) extends Executable {
  def run(): Unit = concurrent.blocking{
    val pool = Executors.newCachedThreadPool() //newWorkStealingPool
    execution.onShutdown("Pool",()⇒{
      val tasks = pool.shutdownNow()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    val server: HttpServer = HttpServer.create(new InetSocketAddress(port),0)
    execution.onShutdown("HttpServer",()⇒server.stop(Int.MaxValue))
    server.setExecutor(pool)
    server.createContext("/", handler)
    server.start()
  }
}

class WorldProviderImpl(
  worldFuture: CompletableFuture[AtomicReference[RichContext]] = new CompletableFuture()
) extends WorldProvider with Observer {
  def createTx(): Context = {
    val global = concurrent.blocking{ worldFuture.get.get }
    new Context(global.injected, global.assembled, Map.empty)
  }
  def activate(global: RichContext): Seq[Observer] = {
    if(worldFuture.isDone) worldFuture.get.set(global)
    else worldFuture.complete(new AtomicReference(global))
    List(this)
  }
}

trait InternetForwarderApp extends ProtocolsApp with InitialObserversApp {
  lazy val worldProvider: WorldProvider with Observer = new WorldProviderImpl()
  override def protocols: List[Protocol] = AuthProtocol :: HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] = worldProvider :: super.initialObservers
}

trait HttpServerApp extends ToStartApp {
  def execution: Execution
  def config: Config
  def worldProvider: WorldProvider
  def httpHandlers: List[RHttpHandler]
  private lazy val httpPort = config.get("C4HTTP_PORT").toInt
  lazy val httpServer: Executable =
    new RHttpServer(httpPort, new ReqHandler(httpHandlers), execution)

  override def toStart: List[Executable] = httpServer :: super.toStart
}

object PostAssembles {
  def apply(mortal: MortalFactory, sseConfig: SSEConfig): List[Assemble] =
    mortal(classOf[S_HttpPost]) :: new PostLifeAssemble(sseConfig) :: Nil
}

case class HttpPostAllow(condition: SrcId)

@assemble class PostLifeAssembleBase(sseConfig: SSEConfig)   {
  type ASessionKey = SrcId
  type Condition = SrcId

  def postsByCondition(
    key: SrcId,
    post: Each[S_HttpPost]
  ): Values[(Condition, S_HttpPost)] =
    List(post.headers.find(_.key=="X-r-branch").map(_.value).getOrElse(post.path) → post)

  def consumersByCondition(
    key: SrcId,
    c: Each[PostConsumer]
  ): Values[(Condition, LocalPostConsumer)] =
    List(WithPK(LocalPostConsumer(c.condition)))

  def lifeToPosts(
    key: SrcId,
    @by[Condition] consumers: Values[LocalPostConsumer],
    @by[Condition] post: Each[S_HttpPost]
  ): Values[(Alive, S_HttpPost)] = //it's not ok if postConsumers.size > 1
    if(consumers.nonEmpty) List(WithPK(post)) else Nil

  def alivePostsBySession(
    key: SrcId,
    @by[Alive] post: Each[S_HttpPost]
  ): Values[(ASessionKey, S_HttpPost)] =
    List(post.headers.find(_.key=="X-r-session").map(_.value).getOrElse("") → post)

  def allowSessionPosts(
    key: SrcId,
    @by[ASessionKey] posts: Values[S_HttpPost]
  ): Values[(SrcId, HttpPostAllow)] =
    for(post ← posts if posts.size <= sseConfig.sessionWaitingPosts || key.isEmpty)
      yield WithPK(HttpPostAllow(post.srcId))
}
