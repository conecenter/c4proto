package ee.cone.c4gate_akka

import akka.stream.ActorMaterializer

import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.model.{HttpRequest,HttpResponse}
import akka.http.scaladsl.HttpExt

trait AkkaMat {
  def get: Future[ActorMaterializer]
}

abstract class AkkaConf {
  def content: String
}

trait AkkaHttp {
  def get: Future[HttpExt]
}

/******************************************************************************/

trait AkkaRequestHandler {
  def pathPrefix: String
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse]
}
