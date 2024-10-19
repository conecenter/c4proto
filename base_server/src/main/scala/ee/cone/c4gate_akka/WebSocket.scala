package ee.cone.c4gate_akka

import akka.NotUsed
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage, UpgradeToWebSocket}
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import ee.cone.c4di.c4

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@c4("AkkaGatewayApp") class WebSocketUtilImpl(akkaMat: AkkaMat) extends WebSocketUtil {
  private def getData(message: Message)(implicit ec: ExecutionContext): Future[ByteString] =
    for {
      mat <- akkaMat.get
      data <- message match {
        case m: BinaryMessage =>
          m.toStrict(1.seconds)(mat).map(_.data)
        case m: TextMessage =>
          m.toStrict(1.seconds)(mat).map(tm=>ByteString(tm.text))
      }
    } yield data
  def toResponse(up: UpgradeToWebSocket, flow: Flow[ByteString, ByteString, NotUsed])(implicit ec: ExecutionContext): HttpResponse =
    up.handleMessages(Flow[Message].mapAsync(1)(getData(_)).via(flow).map(BinaryMessage(_)))
}
