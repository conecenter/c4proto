package ee.cone.c4gate_akka

import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import ee.cone.c4gate_server.AlienConnectionFactory

import scala.concurrent.{ExecutionContext, Future}



@c4("AkkaGatewayApp") final class EventLogHandler(
  webSocketUtil: WebSocketUtil, alienConnectionFactory: AlienConnectionFactory
) extends AkkaRequestHandler {
  def pathPrefix: String = "/eventlog"
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse] =
    req.header[UpgradeToWebSocket].fold(Future.successful(HttpResponse(400, entity = ""))){ upgrade =>
      assert(req.uri.path.toString == pathPrefix)
      val exchange = alienConnectionFactory.create(Single(upgrade.requestedProtocols))
      val source = Source.unfoldAsync(0L){ pos =>
        for((pos,msg) <- Future(exchange.read(pos))) yield Option((pos,ByteString(msg)))
      }
      val sink = Sink.foldAsync[Unit,ByteString]()((_,bs) => Future(exchange.send(bs.utf8String)))
      val flow = Flow.fromSinkAndSourceCoupled(sink, source).watchTermination(){ (nu,doneF) =>
        doneF.onComplete(_=>exchange.stop())
        nu
      }
      Future.successful(webSocketUtil.toResponse(upgrade, flow))
    }
}
