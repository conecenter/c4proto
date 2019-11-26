package ee.cone.c4gate

import akka.stream.ActorMaterializer

import scala.concurrent.{ExecutionContext, Future}

trait AkkaMat {
  def get: Future[ActorMaterializer]
}

trait AkkaInnerResponseHandler{
  def shouldHandle(httpResponse: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse): Boolean
  def handleAsync(
    httpResponse: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse,
    akkaMat: AkkaMat,
  )(
    implicit ec: ExecutionContext,
  ): Future[akka.http.scaladsl.model.HttpResponse]
}