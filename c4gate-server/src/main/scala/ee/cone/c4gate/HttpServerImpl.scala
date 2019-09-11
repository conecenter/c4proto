
package ee.cone.c4gate

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.{E_PostConsumer, U_ToAlienWrite}
import ee.cone.c4gate.AuthProtocol._
import ee.cone.c4proto._
import okio.ByteString

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future, Promise}

object NotFound {
  def apply(): RHttpResponse = RHttpResponse(404,Nil,ByteString.EMPTY)
}

class HttpGetPublicationHandler(worldProvider: WorldProvider) extends RHttpHandler with LazyLogging {
  def handle(request: RHttpRequest)(implicit executionContext: ExecutionContext): Future[RHttpResponse] =
    if(request.method == "GET") worldProvider.createTx.map{ local ⇒
      val path = request.path
      val now = System.currentTimeMillis
      val publicationsByPath = ByPK(classOf[S_HttpPublication]).of(local)
      publicationsByPath.get(path).filter(_.until.forall(now<_)) match {
        case Some(publication) ⇒
          val cTag = request.headers.find(_.key=="If-none-match").map(_.value)
          val sTag = publication.headers.find(_.key=="ETag").map(_.value)
          logger.debug(s"${request.headers}")
          logger.debug(s"$cTag $sTag")
          (cTag,sTag) match {
            case (Some(a),Some(b)) if a == b ⇒ RHttpResponse(304,Nil,ByteString.EMPTY)
            case _ ⇒ RHttpResponse(200,publication.headers,publication.body)
          }
        case _ ⇒ NotFound()
      }
    } else Future.successful(NotFound())

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
  def createHash(password: String, userHashOpt: Option[N_SecureHash]): N_SecureHash =
    pbkdf2(password, userHashOpt.getOrElse(N_SecureHash(64000, 18, generateSalt(24), okio.ByteString.EMPTY)))
  def verify(password: String, correctHash: N_SecureHash): Boolean =
    correctHash == pbkdf2(password, correctHash)
}

class HttpPostHandler(qMessages: QMessages, worldProvider: WorldProvider) extends RHttpHandler with LazyLogging {
  def handle(request: RHttpRequest)(implicit executionContext: ExecutionContext): Future[RHttpResponse] = worldProvider.createTx.map{ local ⇒ request.method match {
    case "POST" ⇒
      val headerMap = request.headers.map(h⇒h.key→h.value).toMap
      val requestId = UUID.randomUUID.toString
      val path = request.path

      val post: okio.ByteString ⇒ S_HttpPost =
        S_HttpPost(requestId, path, request.headers, _, System.currentTimeMillis)
      val authPost: okio.ByteString ⇒ Int ⇒ S_HttpPost = body ⇒ status ⇒
        post(body).copy(headers = N_Header("X-r-auth-status", status.toString) :: request.headers)
      def getPassRegex: Option[String] = ByPK(classOf[C_PasswordRequirements]).of(local).get("gate-password-requirements").map(_.regex)

      val requests: List[Product] = headerMap.get("X-r-auth") match {
        case None ⇒ List(post(request.body))
        case Some("change") ⇒
          val Array(password, again, username) = request.body.utf8().split("\n")
          // 0 - OK, 1 - passwords did not match, 2 - password did not match requirements
          if (password != again)
            List(authPost(okio.ByteString.EMPTY)(1))
          else if (getPassRegex.forall(regex ⇒ regex.isEmpty || password.matches(regex))) {
            val prevHashOpt = ByPK(classOf[C_PasswordHashOfUser]).of(local).get(username).map(_.hash.get)
            val hash: Option[N_SecureHash] = Option(AuthOperations.createHash(password, prevHashOpt))
            List(
              S_PasswordChangeRequest(requestId, hash),
              authPost(okio.ByteString.encodeUtf8(requestId))(0)
            )
          }
          else {
            List(authPost(okio.ByteString.EMPTY)(2))
          }
        case Some("check") ⇒
          val Array(userName,password) = request.body.utf8().split("\n")
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
            U_ToAlienWrite(newId,currentSessionKey,"signedIn",newId,0)
          ) else List(
            post(okio.ByteString.EMPTY)
          )
        case _ ⇒ throw new Exception("unsupported auth action")
      }
      TxAdd(requests.flatMap(LEvent.update)).andThen{ nLocal ⇒
        if(ByPK(classOf[HttpPostAllow]).of(nLocal).contains(requestId)){
          qMessages.send(nLocal)
          val replaceHeaderValues = Map("$C4REQUEST_ID"→requestId)
          val headers = for(
            header ← ByPK(classOf[E_ResponseOptionsByPath]).of(nLocal).get(path).fold(List.empty[N_Header])(_.headers)
          ) yield header.copy(value = replaceHeaderValues.getOrElse(header.value,header.value))
          RHttpResponse(200,headers,ByteString.EMPTY)
        } else {
          logger.warn(s"429 $path")
          logger.debug(s"429 $path ${request.headers}")
          RHttpResponse(429,Nil,ByteString.EMPTY) // Too Many Requests
        }
      }(local)
    case _ ⇒ NotFound()
  }}
}


class SeqRHttpHandler(handlers: List[RHttpHandler]) extends RHttpHandler {
  def handle(request: RHttpRequest)(implicit executionContext: ExecutionContext): Future[RHttpResponse] =
    handlers.foldLeft(Future.successful(NotFound()))( (responseF,handler) ⇒
      responseF.flatMap(response⇒
        if(response.status==404) handler.handle(request) else responseF
      )
    )
}

class WorldProviderImpl(
  worldPromise: Promise[AtomicReference[RichContext]] = Promise()
) extends WorldProvider with Observer {
  def createTx(implicit executionContext: ExecutionContext): Future[Context] = worldPromise.future.map{ globalAtomic ⇒
    val global = globalAtomic.get()
    new Context(global.injected, global.assembled, global.executionContext, Map.empty)
  }
  def activate(global: RichContext): Seq[Observer] = {
    if(worldPromise.future.isCompleted) worldPromise.future.value.get.get.set(global)
    else worldPromise.success(new AtomicReference(global))
    List(this)
  }
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
    c: Each[E_PostConsumer]
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
