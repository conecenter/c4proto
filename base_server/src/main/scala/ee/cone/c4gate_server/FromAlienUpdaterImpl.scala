package ee.cone.c4gate_server

import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.HttpProtocol.N_Header
import scala.concurrent.{ExecutionContext, Future, Promise}

@c4("AbstractHttpGatewayApp") final class FromAlienUpdaterFactoryImpl(
  factory: FromAlienUpdaterImplFactory
) extends FromAlienUpdaterFactory {
  def create(): FromAlienUpdater = factory.create()
}
@c4multi("AbstractHttpGatewayApp") final class FromAlienUpdaterImpl()(
  handler: FHttpHandler
) extends FromAlienUpdater {
  import handler.handle
  private val offlineReqP = Promise[FHttpRequest]()
  def send(until: Long, value: String)(implicit executionContext: ExecutionContext): Future[Long] =
    if(value.isEmpty) Future.successful(until) else parse(value) match {
      case parsed if parsed.get("x-r-op").contains("online") =>
        if(!offlineReqP.isCompleted) offlineReqP.success(req(parsed.updated("value","")))
        val now = System.currentTimeMillis
        if(now > until) handle(req(parsed)).map(_ => now + 30_000) else Future.successful(until)
      case parsed => handle(req(parsed)).map(_ => until)
    }
  def stop()(implicit executionContext: ExecutionContext): Future[Unit] =
    if(offlineReqP.isCompleted) for(req <- offlineReqP.future; _ <- handler.handle(req)) yield ()
  private def req(dict: Map[String,String]): FHttpRequest = {
    val headers =  (("x-r-sync", "save") :: dict.removed("value").toList).sorted.map{ case (k,v) => N_Header(k, v) }
    val body = okio.ByteString.encodeUtf8(dict("value"))
    FHttpRequest("POST", "/connection", None, headers, body)
  }
  private def parse(value: String) =
    value.split("\n").map(line => line.span(_!=':')).groupMapReduce(_._1)(_._2.tail)((a,b)=>"$a\n$b")
}
