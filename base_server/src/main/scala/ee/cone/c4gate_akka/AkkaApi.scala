package ee.cone.c4gate_akka

import akka.stream.ActorMaterializer

import scala.concurrent.{ExecutionContext, Future}
import akka.NotUsed
import akka.util.ByteString
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.ws.UpgradeToWebSocket

trait AkkaMat {
  def get: Future[ActorMaterializer]
}

abstract class AkkaConf {
  def content: String
}

trait AkkaHttp {
  def get: Future[HttpExt]
}

trait AkkaRequestPreHandler {
  def handleAsync(income: HttpRequest)(implicit ec: ExecutionContext): Future[HttpRequest]
}

/******************************************************************************/

trait AkkaRequestHandler {
  def pathPrefix: String
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse]
}

trait RoomFactory {
  def pathPrefix: String
  def createRoom(will: String/*RoomConf*/, killSwitch: SharedKillSwitch): Flow[ByteString,ByteString,NotUsed]
}

trait WebSocketUtil {
  def toResponse(up: UpgradeToWebSocket, flow: Flow[ByteString, ByteString, NotUsed])(implicit ec: ExecutionContext): HttpResponse
}