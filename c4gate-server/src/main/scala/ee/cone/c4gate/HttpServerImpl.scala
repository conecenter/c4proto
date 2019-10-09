
package ee.cone.c4gate

import java.security.SecureRandom
import java.time.Instant
import java.util.{Locale, UUID}
import java.util.concurrent.atomic.AtomicReference

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, CallerAssemble, Single, assemble, by, c4assemble, distinct}
import ee.cone.c4actor._
import ee.cone.c4gate.AlienProtocol.{E_HttpConsumer, U_ToAlienWrite}
import ee.cone.c4gate.AuthProtocol._
import ee.cone.c4gate.HttpProtocolBase.{S_HttpRequest, S_HttpResponse}
import ee.cone.c4proto._
import okio.ByteString

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

@c4("AbstractHttpGatewayApp") class RHttpResponseFactoryImpl extends RHttpResponseFactory {
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse = {
    val resp = S_HttpResponse(request.srcId,200,Nil,ByteString.EMPTY,System.currentTimeMillis)
    RHttpResponse(Option(patch(resp)),Nil)
  }
}

class GetPublicationHttpHandler(httpResponseFactory: RHttpResponseFactory, next: RHttpHandler) extends RHttpHandler with LazyLogging {
  def handle(request: S_HttpRequest, local: Context): RHttpResponse =
    if(request.method == "GET") {
      val path = request.path
      val now = System.currentTimeMillis
      val publicationsByPath = ByPK(classOf[S_HttpPublication]).of(local)
      publicationsByPath.get(path).filter(_.until.forall(now<_)) match {
        case Some(publication) =>
          val cTag = request.headers.find(_.key=="if-none-match").map(_.value)
          val sTag = publication.headers.find(_.key=="etag").map(_.value)
          logger.debug(s"${request.headers}")
          logger.debug(s"$cTag $sTag")
          (cTag,sTag) match {
            case (Some(a),Some(b)) if a == b =>
              httpResponseFactory.directResponse(request,_.copy(status=304))
            case _ =>
              httpResponseFactory.directResponse(request,_.copy(headers=publication.headers,body=publication.body))
          }
        case _ => next.handle(request,local)
      }
    } else next.handle(request,local)

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

class AuthHttpHandler(next: RHttpHandler) extends RHttpHandler with LazyLogging {
  def handle(request: S_HttpRequest, local: Context): RHttpResponse = {
    if(request.method != "POST") next.handle(request,local)
    else ReqGroup.header(request,"x-r-auth") match {
      case None => next.handle(request,local)
      case Some("change") =>
        val authPost: okio.ByteString => Int => S_HttpRequest = body => status =>
          request.copy(headers = N_Header("x-r-auth-status", status.toString) :: request.headers, body = body)
        def getPassRegex: Option[String] = ByPK(classOf[C_PasswordRequirements]).of(local).get("gate-password-requirements").map(_.regex)
        val Array(password, again, username) = request.body.utf8().split("\n")
        // 0 - OK, 1 - passwords did not match, 2 - password did not match requirements
        val requests: List[Product] = if (password != again)
          authPost(okio.ByteString.EMPTY)(1) :: Nil
        else if (getPassRegex.forall(regex => regex.isEmpty || password.matches(regex))) {
          val prevHashOpt = ByPK(classOf[C_PasswordHashOfUser]).of(local).get(username).map(_.hash.get)
          val hash: Option[N_SecureHash] = Option(AuthOperations.createHash(password, prevHashOpt))
          S_PasswordChangeRequest(request.srcId, hash) ::
            authPost(okio.ByteString.encodeUtf8(request.srcId))(0) :: Nil
        }
        else {
          authPost(okio.ByteString.EMPTY)(2) :: Nil
        }
        RHttpResponse(None, requests.flatMap(LEvent.update))
      case Some("check") =>
        val Array(userName,password) = request.body.utf8().split("\n")
        val hashesByUser = ByPK(classOf[C_PasswordHashOfUser]).of(local)
        val hash = hashesByUser.get(userName).map(_.hash.get)
        val endTime = System.currentTimeMillis() + 1000
        val hashOK = hash.exists(pass=>AuthOperations.verify(password,pass))
        Thread.sleep(Math.max(0,endTime-System.currentTimeMillis()))
        val currentSessionKey = ReqGroup.session(request).get
        val newId = UUID.randomUUID.toString
        val post: okio.ByteString => S_HttpRequest = b => request.copy(body = b)
        val requests: List[Product] = if(hashOK) List(
          post(ToByteString(newId)),
          U_AuthenticatedSession(newId, userName, Instant.now.plusSeconds(20).getEpochSecond),
          U_ToAlienWrite(newId,currentSessionKey,"signedIn",newId,0)
        ) else List(
          post(okio.ByteString.EMPTY)
        )
        RHttpResponse(None, requests.flatMap(LEvent.update))
      case _ => throw new Exception("unsupported auth action")
    }
  }
}

class DefSyncHttpHandler() extends RHttpHandler with LazyLogging {
  def handle(request: S_HttpRequest, local: Context): RHttpResponse =
    RHttpResponse(None, LEvent.update(request).toList)
}

class NotFoundProtectionHttpHandler(httpResponseFactory: RHttpResponseFactory, next: RHttpHandler) extends RHttpHandler with LazyLogging {
  def handle(request: S_HttpRequest, local: Context): RHttpResponse = {
    val index = ByPK(classOf[LocalHttpConsumerExists]).of(local)
    if(ReqGroup.conditions(request).flatMap(cond=>index.get(cond)).nonEmpty)
      next.handle(request,local)
    else {
      logger.warn(s"404 ${request.path}")
      logger.trace(index.keys.toList.sorted.mkString(", "))
      httpResponseFactory.directResponse(request,_.copy(status=404))
    }
  }
}

class SelfDosProtectionHttpHandler(httpResponseFactory: RHttpResponseFactory, sseConfig: SSEConfig, next: RHttpHandler) extends RHttpHandler with LazyLogging {
  def handle(request: S_HttpRequest, local: Context): RHttpResponse =
    if((for{
      sessionKey <- ReqGroup.session(request)
      count <- ByPK(classOf[HttpRequestCount]).of(local).get(sessionKey) if count.count > sseConfig.sessionWaitingRequests
    } yield true).nonEmpty){
      logger.warn(s"429 ${request.path}")
      logger.debug(s"429 ${request.path} ${request.headers}")
      httpResponseFactory.directResponse(request,_.copy(status=429)) // Too Many Requests
    } else next.handle(request,local)
}

@c4("SSEServerApp") class HttpReqAssemblesBase(mortal: MortalFactory, sseConfig: SSEConfig) {
  @provide def subAssembles: Seq[Assemble] =
    mortal(classOf[S_HttpRequest]) :: new PostLifeAssemble() :: Nil
}

case class HttpRequestCount(sessionKey: SrcId, count: Long)
case class LocalHttpConsumerExists(condition: String)

@assemble class PostLifeAssembleBase()   {
  type ASessionKey = SrcId
  type Condition = SrcId

  def requestsByCondition(
    key: SrcId,
    request: Each[S_HttpRequest]
  ): Values[(Condition, S_HttpRequest)] =
    ReqGroup.conditions(request).map(_->request)

  def consumersByCondition(
    key: SrcId,
    c: Each[E_HttpConsumer]
  ): Values[(Condition, LocalHttpConsumer)] =
    List(WithPK(LocalHttpConsumer(c.condition)))

  def consumerExists(
    key: SrcId,
    @by[Condition] consumers: Values[LocalHttpConsumer],//it's not ok if postConsumers.size > 1
  ): Values[(SrcId, LocalHttpConsumerExists)] =
      WithPK(LocalHttpConsumerExists(key)) :: Nil

  def lifeToRequests(
    key: SrcId,
    consumers: Each[LocalHttpConsumerExists],
    @by[Condition] req: Each[S_HttpRequest]
  ): Values[(Alive, S_HttpRequest)] =
    List(WithPK(req))

  def aliveBySession(
    key: SrcId,
    @distinct @by[Alive] request: Each[S_HttpRequest]
  ): Values[(ASessionKey, S_HttpRequest)] =
    ReqGroup.session(request).map(_->request).toList

  def count(
    key: SrcId,
    @by[ASessionKey] requests: Values[S_HttpRequest]
  ): Values[(SrcId, HttpRequestCount)] =
    WithPK(HttpRequestCount(key,requests.size)) :: Nil
}

object ReqGroup {
  def session(request: S_HttpRequest): Option[String] =
    header(request,"x-r-session").filter(_.nonEmpty)
  def conditions(request: S_HttpRequest): List[String] =
    header(request,"x-r-branch").toList ::: genCond(request.path)
  private def genCond(path: String) = {
    val index = path.lastIndexOf("/")
    if(index < 0) List(path) else List(s"${path.substring(0,index)}/*",path)
  }
  def header(request: S_HttpRequest, key: String): Option[String] =
    request.headers.find(_.key == key).map(_.value)
}

class FHttpHandlerImpl(
  worldProvider: WorldProvider,
  httpResponseFactory: RHttpResponseFactory,
  handler: RHttpHandler
) extends FHttpHandler with LazyLogging {
  def handle(request: FHttpRequest)(implicit executionContext: ExecutionContext): Future[S_HttpResponse] = {
    val now = System.currentTimeMillis
    val res = for{
      local <- worldProvider.sync(None)
      headers = normalize(request.headers)
      requestEv = S_HttpRequest(UUID.randomUUID.toString, request.method, request.path, headers, request.body, now)
      result = handler.handle(requestEv,local)
      uLocal = TxAdd(result.events)(local)
      cLocal <- worldProvider.sync(Option(uLocal))
      response <- result.instantResponse.fold{new WaitFor(requestEv).iteration(cLocal)}(Future.successful)
    } yield response.copy(headers = normalize(response.headers))
    for(e <- res.failed) logger.error("http handling error",e)
    res
  }
  def normalize(headers: List[N_Header]): List[N_Header] =
    headers.map(h=>h.copy(key = h.key.toLowerCase(Locale.ENGLISH)))
  class WaitFor(
    request: S_HttpRequest,
    requestByPK: ByPrimaryKeyGetter[S_HttpRequest] = ByPK(classOf[S_HttpRequest]),
    responseByPK: ByPrimaryKeyGetter[S_HttpResponse] = ByPK(classOf[S_HttpResponse])
  )(implicit executionContext: ExecutionContext) {
    def iteration(local: Context): Future[S_HttpResponse] = if(requestByPK.of(local).get(request.srcId).nonEmpty){
      worldProvider.sync(Option(local)).flatMap(iteration)
    } else {
      val responseOpt = responseByPK.of(local).get(request.srcId)
      val response = responseOpt.orElse(httpResponseFactory.directResponse(request,a=>a).instantResponse).get
      val events = responseOpt.toList.flatMap(LEvent.delete)
      val uLocal = TxAdd(events)(local)
      for { _ <- worldProvider.sync(Option(uLocal)) } yield response
    }
  }
}
