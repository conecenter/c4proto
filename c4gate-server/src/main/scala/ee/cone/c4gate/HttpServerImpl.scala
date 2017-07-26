
package ee.cone.c4gate

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CompletableFuture, Executors, TimeUnit}
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.{PostConsumer, ToAlienWrite}
import ee.cone.c4gate.AuthProtocol._
import ee.cone.c4proto._

import scala.collection.immutable.Seq
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
      case Seq(publication) ⇒
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
    ToByteString(salt)
  }
  private def pbkdf2(password: String, template: SecureHash): SecureHash = {
    val spec = new PBEKeySpec(password.toCharArray, template.salt.toByteArray, template.iterations, template.hashSizeInBytes * 8)
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    template.copy(hash=ToByteString(skf.generateSecret(spec).getEncoded))
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

    val requests: List[Product] = headerMap.get("X-r-auth") match {
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
        if(hashOK) List(
          post(ToByteString(newId)),
          AuthenticatedSession(newId, userName),
          ToAlienWrite(newId,currentSessionKey,"signedIn",newId,0)
        ) else List(
          post(okio.ByteString.EMPTY)
        )
      case _ ⇒ throw new Exception("unsupported auth action")
    }
    LEvent.add(requests.flatMap(LEvent.update)).andThen{ nLocal ⇒
      val world = TxKey.of(nLocal).world
      if(By.srcId(classOf[HttpPostAllow]).of(world).getOrElse(requestId,Nil).nonEmpty){
        qMessages.send(nLocal)
        httpExchange.sendResponseHeaders(200, 0)
      } else {
        println(path)
        httpExchange.sendResponseHeaders(429, 0) //Too Many Requests
      }
    }(local)
    true
  }
}

class ReqHandler(handlers: List[RHttpHandler]) extends HttpHandler {
  def handle(httpExchange: HttpExchange) =
    Trace{ FinallyClose[HttpExchange,Unit](_.close())(httpExchange) { ex ⇒ handlers.find(_.handle(ex)) } }
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
  reducer: Reducer,
  worldFuture: CompletableFuture[AtomicReference[World]] = new CompletableFuture()
) extends WorldProvider with Observer {
  def createTx(): World = reducer.createTx(worldFuture.get.get)(Map())
  def activate(world: World): Seq[Observer] = {
    if(worldFuture.isDone) worldFuture.get.set(world)
    else worldFuture.complete(new AtomicReference(world))
    List(this)
  }
}

trait InternetForwarderApp extends ProtocolsApp with InitialObserversApp {
  def qReducer: Reducer
  lazy val worldProvider: WorldProvider with Observer = new WorldProviderImpl(qReducer)
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
    new RHttpServer(httpPort, new ReqHandler(new HttpGetHandler(worldProvider) :: httpHandlers), execution)

  override def toStart: List[Executable] = httpServer :: super.toStart
}

object PostAssembles {
  def apply(mortal: MortalFactory, sseConfig: SSEConfig): List[Assemble] =
    mortal(classOf[HttpPost]) :: new PostLifeAssemble(sseConfig) :: Nil
}

case class HttpPostAllow(condition: SrcId)

@assemble class PostLifeAssemble(sseConfig: SSEConfig) extends Assemble {
  type ASessionKey = SrcId
  type Condition = SrcId

  def postsByCondition(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(Condition, HttpPost)] =
    for(post ← posts)
      yield post.headers.find(_.key=="X-r-branch").map(_.value).getOrElse(post.path) → post

  def consumersByCondition(
    key: SrcId,
    consumers: Values[PostConsumer]
  ): Values[(Condition, LocalPostConsumer)] =
    for(c ← consumers) yield WithSrcId(LocalPostConsumer(c.condition))

  def lifeToPosts(
    key: SrcId,
    @by[Condition] consumers: Values[LocalPostConsumer],
    @by[Condition] posts: Values[HttpPost]
  ): Values[(Alive, HttpPost)] = //it's not ok if postConsumers.size > 1
    for(post ← posts if consumers.nonEmpty) yield WithSrcId(post)

  def alivePostsBySession(
    key: SrcId,
    @by[Alive] posts: Values[HttpPost]
  ): Values[(ASessionKey, HttpPost)] =
    for(post ← posts)
      yield post.headers.find(_.key=="X-r-session").map(_.value).getOrElse("") → post

  def allowSessionPosts(
    key: SrcId,
    @by[ASessionKey] posts: Values[HttpPost]
  ): Values[(SrcId, HttpPostAllow)] =
    for(post ← posts if posts.size <= sseConfig.sessionWaitingPosts || key.isEmpty)
      yield WithSrcId(HttpPostAllow(post.srcId))
}
