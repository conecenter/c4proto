package ee.cone.c4gate_akka

import akka.stream.ActorMaterializer

import scala.concurrent.Future

trait AkkaMat {
  def get: Future[ActorMaterializer]
}

abstract class AkkaConf {
  def content: String
}