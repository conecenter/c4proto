package ee.cone.c4gate_server

import ee.cone.c4actor.Execution
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.HttpProtocol.N_Header

import scala.concurrent.{ExecutionContext, Future, Promise}

@c4("AbstractHttpGatewayApp") final class FromAlienUpdaterFactoryImpl(
  factory: FromAlienUpdaterImplFactory
) extends FromAlienUpdaterFactory {
  def create(): FromAlienUpdater = factory.create()
}
@c4multi("AbstractHttpGatewayApp") final class FromAlienUpdaterImpl()(
  handler: FHttpHandler, execution: Execution
) extends FromAlienUpdater {
  import handler.handle
  private val offlineReqP = Promise[FHttpRequest]()
  def send(until: Long, value: String)(implicit executionContext: ExecutionContext): Future[Long] =
    if(value.isEmpty) Future.successful(until) else parse(value) match {
      case parsed if parsed.get("x-r-op").contains("online") =>
        if(!offlineReqP.isCompleted) execution.success(offlineReqP, req(parsed.updated("value","")))
        val now = System.currentTimeMillis
        if(now > until) handle(req(parsed)).map(_ => now + 30_000) else Future.successful(until)
      case parsed => handle(req(parsed)).map(_ => until)
    }
  def stop()(implicit executionContext: ExecutionContext): Future[Unit] =
    if(offlineReqP.isCompleted) for(req <- offlineReqP.future; _ <- handle(req)) yield ()
    else Future.successful(())
  private def req(dict: Map[String,String]): FHttpRequest = {
    val headers =  (("x-r-sync", "save") :: dict.removed("value").toList).sorted.map{ case (k,v) => N_Header(k, v) }
    val body = okio.ByteString.encodeUtf8(dict("value"))
    FHttpRequest("POST", "/connection", None, headers, body)
  }
  private def parse(value: String) =
    value.split("\n").map(line => line.span(_!=':')).groupMapReduce(_._1)(_._2.tail)((a,b)=>"$a\n$b")
}

/*
  ws:
  check log/session exists
  dos protection with backpressure (from SelfDosProtectionHttpHandler)
  die if x-r-auth; work with reqId on react level
  saving handler

  http statuses do not affect socket -- keep delivery!

  session do not need consumer
*/

/*
@c4("AbstractHttpGatewayApp") final class SelfDosProtectionHttpHandler(
  httpResponseFactory: RHttpResponseFactory,
  getHttpRequestCount: GetByPK[HttpRequestCount],
) extends LazyLogging {
  val sessionWaitingRequests = 8
  def wire: RHttpHandlerCreate = next => (request,local) =>
    if((for{
      sessionKey <- ReqGroup.session(request)
      count <- getHttpRequestCount.ofA(local).get(sessionKey) if count.count > sessionWaitingRequests
    } yield true).nonEmpty){
      logger.warn(s"429 ${request.path}")
      logger.debug(s"429 ${request.path} ${request.headers}")
      httpResponseFactory.directResponse(request,_.copy(status=429)) // Too Many Requests
    } else next(request,local)
}*/
/*
  def aliveBySession(
    key: SrcId,
    @distinct @by[Alive] request: Each[S_HttpRequest]
  ): Values[(ASessionKey, S_HttpRequest)] =
    ReqGroup.session(request).map(_->request).toList

  def count(
    key: SrcId,
    @by[ASessionKey] requests: Values[S_HttpRequest]
  ): Values[(SrcId, HttpRequestCount)] =
    WithPK(HttpRequestCount(key,requests.size)) :: Nil*/
case class HttpRequestCount(sessionKey: SrcId, count: Long)

+wishlist send
  online/offline
protect by sessionKey
wishlist recv gate
wishlist recv main
wishlist purge
  redraw

FromAlienStatus life?
