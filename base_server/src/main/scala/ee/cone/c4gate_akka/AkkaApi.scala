package ee.cone.c4gate_akka

import scala.concurrent.{ExecutionContext, Future}
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.{HttpRequest,HttpResponse}

trait AkkaMat {
  def get: Future[ActorMaterializer]
}

trait AkkaRequestHandler {
  def pathPrefix: String
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Future[HttpResponse]
}
