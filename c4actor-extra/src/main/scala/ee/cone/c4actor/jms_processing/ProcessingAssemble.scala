package ee.cone.c4actor.jms_processing

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.jms_processing.JmsProcessingIndexes.AllOrigsExtractedIndex
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, CallerAssemble}

object JmsProcessingIndexes {
  type AllOrigsExtractedIndex = SrcId
  lazy val AllOrigsExtractedKey = "AllOrigsExtractedKey"
}

trait ProcessingAssemble[Model <: Product] extends CallerAssemble {

  def modelCl: Class[Model]

  override def subAssembles: List[Assemble] = new CaseClassOfXmlSubAssemble[Model] :: Nil

}

class CaseClassOfXmlSubAssemble[Orig <: Product] extends Assemble {
  def AllOrigsExtractedJoiner(
    key: SrcId,
    model: Each[Orig]
  ): Values[(AllOrigsExtractedIndex, Product)] = List(JmsProcessingIndexes.AllOrigsExtractedKey -> model)
}