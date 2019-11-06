package ee.cone.c4gate

import akka.stream.ActorMaterializer

import scala.concurrent.{ExecutionContext, Future}

trait AkkaMat {
  def get: Future[ActorMaterializer]
}

trait AkkaAdditionalHandler{
  def shouldHandle(httpRequest: akka.http.scaladsl.model.HttpRequest): Boolean
  def handleAsync(httpRequest: akka.http.scaladsl.model.HttpRequest, akkaMat: AkkaMat)(implicit ec: ExecutionContext): Future[akka.http.scaladsl.model.HttpResponse]
  def handle(httpRequest: akka.http.scaladsl.model.HttpRequest, akkaMat: AkkaMat): akka.http.scaladsl.model.HttpResponse
}