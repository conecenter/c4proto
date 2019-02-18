package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update

import scala.collection.immutable.Seq

trait ExtModelsApp {
  def external: List[Class[_ <: Product]] = Nil
}

trait ExtUpdateProcessorApp {
  def extUpdateProcessor: ExtUpdateProcessor
}

trait ExtUpdateProcessor {
  def process(updates: Seq[Update]): Seq[Update]
  def idSet: Set[Long]
}
