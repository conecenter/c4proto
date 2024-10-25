package ee.cone.c4gate_akka

import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import ee.cone.c4di.c4
import ee.cone.c4gate_server.{AsyncEventLogReader, FromAlienStatusUpdater}
import scala.concurrent.{ExecutionContext, Future}

@c4("AkkaGatewayApp") final class EventLogHandler(
  webSocketUtil: WebSocketUtil, asyncEventLogReader: AsyncEventLogReader, fromAlienStatusUpdater: FromAlienStatusUpdater
) extends AkkaRequestHandler {
  def pathPrefix: String = "/eventlog/"
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] =
    req.header[UpgradeToWebSocket].fold(Future.successful(HttpResponse(400, entity = ""))){ upgrade =>
      val logKey = req.uri.path.toString.substring(pathPrefix.length)
      val source = Source.unfoldAsync(0L){ pos =>
        asyncEventLogReader.read(logKey, pos).map{ case (pos,msg) => Option((pos,ByteString(msg))) }
      }
      val sink = Sink.foreach[ByteString](bs=>fromAlienStatusUpdater.pong(logKey, bs.utf8String))
      val flow = Flow.fromSinkAndSourceCoupled(sink, source)
      Future.successful(webSocketUtil.toResponse(upgrade, flow))
    }
}
