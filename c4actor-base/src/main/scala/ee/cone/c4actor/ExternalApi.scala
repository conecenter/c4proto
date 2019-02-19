package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update

import scala.collection.immutable.Seq

object ExternalModel{
  def apply[Model <: Product](cl: Class[Model]): ExternalModel[Model] = ExternalModel(cl.getName)(cl)
}

case class ExternalModel[Model <: Product](clName: String)(val cl: Class[Model])

trait ExtModelsApp {
  def extModels: List[ExternalModel[_ <: Product]] = Nil
}

trait ExtUpdateProcessorApp {
  def extUpdateProcessor: ExtUpdateProcessor
}

trait ExtUpdateProcessor {
  def process(updates: Seq[Update]): Seq[Update]
  def idSet: Set[Long]
}
