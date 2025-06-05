
package ee.cone.c4gate_server

import java.util.{Locale, UUID}
import com.typesafe.scalalogging.LazyLogging
import okio.ByteString
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.c4assemble
import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol.{S_HttpRequest, S_HttpResponse}
import ee.cone.c4di._
import ee.cone.c4gate._
import ee.cone.c4gate_server.RHttpTypes.{RHttpHandler, RHttpHandlerCreate}

@c4("AbstractHttpGatewayApp") final class RHttpResponseFactoryImpl extends RHttpResponseFactory {
  def response(request: S_HttpRequest): S_HttpResponse =
    S_HttpResponse(request.srcId,200,Nil,ByteString.EMPTY,System.currentTimeMillis)
  def directResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse): RHttpResponse =
    RHttpResponse(patch(response(request)), Nil)
  def deferredResponse(request: S_HttpRequest, patch: S_HttpResponse=>S_HttpResponse, events: LEvents): RHttpResponse =
    RHttpResponse(patch(response(request)), events)
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

@c4("AbstractHttpGatewayApp") final class DefSyncHttpHandler(
  httpResponseFactory: RHttpResponseFactory,
  getLocalHttpConsumerExists: GetByPK[LocalHttpConsumerExists],
) extends LazyLogging {
  def wire: RHttpHandler = (request,local) => {
    val index = getLocalHttpConsumerExists.ofA(local)
    if(ReqGroup.conditions(request).flatMap(cond=>index.get(cond)).nonEmpty)
      httpResponseFactory.deferredResponse(request, r=>r, LEvent.update(request).toList)
    else {
      logger.warn(s"404 ${request.path}")
      logger.trace(index.keys.toList.sorted.mkString(", "))
      httpResponseFactory.directResponse(request,_.copy(status=404))
    }
  }
}

case class LocalHttpConsumerExists(condition: String)

@c4assemble("AbstractHttpGatewayApp") class PostLifeAssembleBase {
  def consumerExists(key: SrcId, consumers: Values[LocalHttpConsumer]): Values[(SrcId, LocalHttpConsumerExists)] =
    Seq(WithPK(LocalHttpConsumerExists(key))) //it's not ok if postConsumers.size > 1
}

object ReqGroup {
  def conditions(request: S_HttpRequest): List[String] = {
    val path = request.path
    val index = path.lastIndexOf("/")
    if(index < 0) List(path) else List(s"${path.substring(0,index)}/*",path)
  }
}

@c4multi("AbstractHttpGatewayApp") final class FHttpHandlerImpl(handler: RHttpHandler)(
  worldProvider: WorldProvider, requestByPK: GetByPK[S_HttpRequest], responseByPK: GetByPK[S_HttpResponse],
) extends FHttpHandler with LazyLogging {
  import WorldProvider._
  def handle(request: FHttpRequest): S_HttpResponse = {
    val now = System.currentTimeMillis
    val headers = normalize(request.headers)
    val id = UUID.randomUUID.toString
    val requestEv = S_HttpRequest(id, request.method, request.path, request.rawQueryString, headers, request.body, now)
    val resp: S_HttpResponse = worldProvider.run(List(
      world => handler(requestEv, new Context(world.assembled, Map.empty)) match {
        case result if result.events.isEmpty => Stop(result.response)
        case result => Next(LEvent.update(result.response) ++ result.events)
      },
      world => {
        val resp = responseByPK.ofA(world)(id) // need to fail here if resp was not saved
        if(requestByPK.ofA(world).contains(id)) Redo() else Stop(resp)
      },
    ):Steps[S_HttpResponse])
    worldProvider.runUpdCheck(world => responseByPK.ofA(world).get(id).toSeq.flatMap(LEvent.delete))
    resp
  }
  private def normalize(headers: List[N_Header]): List[N_Header] =
    headers.map(h=>h.copy(key = h.key.toLowerCase(Locale.ENGLISH)))
}
