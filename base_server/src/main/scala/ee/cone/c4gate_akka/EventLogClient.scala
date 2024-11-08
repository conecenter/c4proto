package ee.cone.c4gate_akka

import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import ee.cone.c4di.c4
import ee.cone.c4gate_server.{EventLogReader, FromAlienUpdaterFactory}
import scala.concurrent.{ExecutionContext, Future}

@c4("AkkaGatewayApp") final class EventLogHandler(
  webSocketUtil: WebSocketUtil, eventLogReader: EventLogReader, fromAlienUpdaterFactory: FromAlienUpdaterFactory,
) extends AkkaRequestHandler {
  def pathPrefix: String = "/eventlog/"
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] =
    req.header[UpgradeToWebSocket].fold(Future.successful(HttpResponse(400, entity = ""))){ upgrade =>
      val logKey = req.uri.path.toString.substring(pathPrefix.length)
      val source = Source.unfoldAsync(0L){ pos =>
        for((pos,msg) <- Future(eventLogReader.read(logKey, pos))) yield Option((pos,ByteString(msg)))
      }
      val updater = fromAlienUpdaterFactory.create(logKey)
      val sink = Sink.foreach[ByteString](bs => updater.send(bs.utf8String))
      val flow = Flow.fromSinkAndSourceCoupled(sink, source).watchTermination(){ (nu,doneF) =>
        doneF.onComplete(_=>updater.stop())
        nu
      }
      Future.successful(webSocketUtil.toResponse(upgrade, flow))
    }
}
