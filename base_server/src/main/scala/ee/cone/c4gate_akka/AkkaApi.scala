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

trait AkkaRequestPreHandler {
  def handleAsync(income: HttpRequest)(implicit ec: ExecutionContext): Future[HttpRequest]
}
