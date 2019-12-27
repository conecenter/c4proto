package ee.cone.c4gate_akka

import akka.stream.ActorMaterializer

import scala.concurrent.{ExecutionContext, Future}

trait AkkaMat {
  def get: Future[ActorMaterializer]
}

abstract class AkkaConf {
  def content: String
}

/*
trait C4AkkaHandler[-Income, +Outcome]{
  def handleAsync(
    income: Income,
    akkaMat: AkkaMat,
  )(
    implicit ec: ExecutionContext,
  ): Future[Outcome]
}
trait AkkaResponseHandler extends C4AkkaHandler[ee.cone.c4gate.HttpProtocolBase.S_HttpResponse, akka.http.scaladsl.model.HttpResponse]
trait AkkaRequestHandler extends C4AkkaHandler[akka.http.scaladsl.model.HttpRequest, akka.http.scaladsl.model.HttpRequest]
trait AkkaResponseHandlerProvider{
  def get: AkkaResponseHandler
}
trait AkkaRequestHandlerProvider{
  def get: AkkaRequestHandler
}
*/