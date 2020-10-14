
package ee.cone.c4gate_server

import java.security.SecureRandom
import java.time.Instant
import java.util.{Locale, UUID}

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
import ee.cone.c4gate.HttpProtocol.{S_HttpRequest, S_HttpResponse}
import ee.cone.c4proto._
import okio.ByteString
import ee.cone.c4di._
import ee.cone.c4gate._
import ee.cone.c4gate_server.RHttpTypes.{RHttpHandler, RHttpHandlerCreate}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

@c4("AbstractHttpGatewayApp") final class RHttpResponseFactoryImpl extends RHttpResponseFactory {
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse = {
    val resp = S_HttpResponse(request.srcId,200,Nil,ByteString.EMPTY,System.currentTimeMillis)
    RHttpResponse(Option(patch(resp)),Nil)
  }
  def setSession(request: S_HttpRequest, userName: Option[String], wasSession: Option[U_AuthenticatedSession]): RHttpResponse = {
    val sessionOpt = userName.map(n=>U_AuthenticatedSession(UUID.randomUUID.toString, n , Instant.now.plusSeconds(20).getEpochSecond, request.headers))
    val events = sessionOpt.toList.flatMap(LEvent.update) ++ wasSession.toList.flatMap(LEvent.delete)
    val header = N_Header("x-r-set-session",sessionOpt.fold("")(_.sessionKey))
    directResponse(request,r=>r.copy(headers = header :: r.headers)).copy(events=events)
  }
}

@c4("AbstractHttpGatewayApp") final class GetPublicationHttpHandler(
  httpResponseFactory: RHttpResponseFactory,
  getByPathHttpPublication: GetByPK[ByPathHttpPublication],
  getByPathHttpPublicationUntil: GetByPK[ByPathHttpPublicationUntil],
) extends LazyLogging {
  def wire: RHttpHandlerCreate = next => (request,local) =>
    if(request.method == "GET") {
      val path = request.path
      val now = System.currentTimeMillis
      val publication = for {
        until <- getByPathHttpPublicationUntil.ofA(local).get(path) if now < until.until
        p <- getByPathHttpPublication.ofA(local).get(path)
      } yield p
      publication match {
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
        case _ => next(request,local)
      }
    } else next(request,local)

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

@c4("AbstractHttpGatewayApp") final class AuthHttpHandler(
  httpResponseFactory: RHttpResponseFactory,
  getC_PasswordRequirements: GetByPK[C_PasswordRequirements],
  getC_PasswordHashOfUser: GetByPK[C_PasswordHashOfUser],
  getU_AuthenticatedSession: GetByPK[U_AuthenticatedSession],
) extends LazyLogging {

  private def getSession(request: S_HttpRequest, local: Context): U_AuthenticatedSession = {
    val wasSessionKey = ReqGroup.session(request).filter(_.nonEmpty).get
    getU_AuthenticatedSession.ofA(local)(wasSessionKey)
  }

  def wire: RHttpHandlerCreate = next => (request,local) => {
    if(request.method != "POST") next(request,local)
    else ReqGroup.header(request,"x-r-auth") match {
      case None => next(request,local)
      case Some("change") =>
        val authPost: okio.ByteString => Int => S_HttpRequest = body => status =>
          request.copy(headers = N_Header("x-r-auth-status", status.toString) :: request.headers, body = body)
        def getPassRegex: Option[String] = getC_PasswordRequirements.ofA(local).get("gate-password-requirements").map(_.regex)
        val password :: again :: usernameAdd = request.body.utf8().split("\n").toList
        // 0 - OK, 1 - passwords did not match, 2 - password did not match requirements
        val requests: List[Product] = if (password != again)
          authPost(okio.ByteString.EMPTY)(1) :: Nil
        else if (getPassRegex.forall(regex => regex.isEmpty || password.matches(regex))) {
          val prevHashOpt = Single.option(usernameAdd)
            .flatMap(getC_PasswordHashOfUser.ofA(local).get)
            .map(_.hash.get)
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
        val hashesByUser = getC_PasswordHashOfUser.ofA(local)
        val hash = hashesByUser.get(userName).map(_.hash.get)
        val endTime = System.currentTimeMillis() + 1000
        val hashOK = hash.exists(pass=>AuthOperations.verify(password,pass))
        Thread.sleep(Math.max(0,endTime-System.currentTimeMillis()))
        if(hashOK) {
          val wasSessionKey = ReqGroup.session(request).filter(_.nonEmpty).get
          val wasSession = getU_AuthenticatedSession.ofA(local)(wasSessionKey)
          httpResponseFactory.setSession(request,Option(userName),Option(wasSession))
        }
        else RHttpResponse(None, LEvent.update(request.copy(body = okio.ByteString.EMPTY)).toList)
      case _ => throw new Exception("unsupported auth action")
    }
  }
}
// no "signedIn"

@c4("AbstractHttpGatewayApp") final class DefSyncHttpHandler() extends LazyLogging {
  def wire: RHttpHandler = (request,local) =>
    RHttpResponse(None, LEvent.update(request).toList)
}

@c4("AbstractHttpGatewayApp") final class NotFoundProtectionHttpHandler(
  httpResponseFactory: RHttpResponseFactory,
  getLocalHttpConsumerExists: GetByPK[LocalHttpConsumerExists],
) extends LazyLogging {
  def wire: RHttpHandlerCreate = next => (request,local) => {
    val index = getLocalHttpConsumerExists.ofA(local)
    if(ReqGroup.conditions(request).flatMap(cond=>index.get(cond)).nonEmpty)
      next(request,local)
    else {
      logger.warn(s"404 ${request.path}")
      logger.trace(index.keys.toList.sorted.mkString(", "))
      httpResponseFactory.directResponse(request,_.copy(status=404))
    }
  }
}

@c4("AbstractHttpGatewayApp") final class SelfDosProtectionHttpHandler(
  httpResponseFactory: RHttpResponseFactory, sseConfig: SSEConfig,
  getHttpRequestCount: GetByPK[HttpRequestCount],
) extends LazyLogging {
  def wire: RHttpHandlerCreate = next => (request,local) =>
    if((for{
      sessionKey <- ReqGroup.session(request)
      count <- getHttpRequestCount.ofA(local).get(sessionKey) if count.count > sseConfig.sessionWaitingRequests
    } yield true).nonEmpty){
      logger.warn(s"429 ${request.path}")
      logger.debug(s"429 ${request.path} ${request.headers}")
      httpResponseFactory.directResponse(request,_.copy(status=429)) // Too Many Requests
    } else next(request,local)
}

@c4("SSEServerApp") final class HttpReqAssemblesBase(mortal: MortalFactory, sseConfig: SSEConfig) {
  @provide def subAssembles: Seq[Assemble] = List(mortal(classOf[S_HttpRequest]))
}

case class HttpRequestCount(sessionKey: SrcId, count: Long)
case class LocalHttpConsumerExists(condition: String)

@c4assemble("SSEServerApp") class PostLifeAssembleBase()   {
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

@c4multi("AbstractHttpGatewayApp") final class FHttpHandlerImpl(handler: RHttpHandler)(
  worldProvider: WorldProvider,
  httpResponseFactory: RHttpResponseFactory,
  getS_HttpRequest: GetByPK[S_HttpRequest],
  getS_HttpResponse: GetByPK[S_HttpResponse],
) extends FHttpHandler with LazyLogging {
  def handle(request: FHttpRequest)(implicit executionContext: ExecutionContext): Future[S_HttpResponse] = {
    val now = System.currentTimeMillis
    val headers = normalize(request.headers)
    val requestEv = S_HttpRequest(UUID.randomUUID.toString, request.method, request.path, request.rawQueryString, headers, request.body, now)
    val res = worldProvider.tx{ local =>
      val result = handler(requestEv,local)
      (result.events,result.instantResponse)
    }.flatMap(new WaitFor(requestEv).iteration)
    for(e <- res.failed) logger.error("http handling error",e)
    res
  }
  def normalize(headers: List[N_Header]): List[N_Header] =
    headers.map(h=>h.copy(key = h.key.toLowerCase(Locale.ENGLISH)))
  class WaitFor(
    request: S_HttpRequest,
    requestByPK: GetByPK[S_HttpRequest] = getS_HttpRequest,
    responseByPK: GetByPK[S_HttpResponse] = getS_HttpResponse
  )(implicit executionContext: ExecutionContext) {
    def iteration(txRes: TxRes[Option[S_HttpResponse]]): Future[S_HttpResponse] =
      txRes.value.fold(
        txRes.next.tx{ local =>
          if(requestByPK.ofA(local).get(request.srcId).nonEmpty) (Nil,None)
          else {
            val responseOpt = responseByPK.ofA(local).get(request.srcId)
            val response = responseOpt.orElse(httpResponseFactory.directResponse(request,a=>a).instantResponse).get
            val events = responseOpt.toList.flatMap(LEvent.delete)
            (events,Option(response))
          }
        }.flatMap(iteration)
      )(response =>
        Future.successful(response.copy(headers = normalize(response.headers)))
      )
  }
}
