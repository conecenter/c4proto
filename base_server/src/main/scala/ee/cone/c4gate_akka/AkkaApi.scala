package ee.cone.c4gate_akka

import akka.stream.ActorMaterializer

import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.model.HttpRequest

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