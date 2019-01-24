package ee.cone.c4actor.jms_processing

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.jms_processing.JmsProcessingIndexes.{AllCaseClassOfXmlFormOrigsDoneIndex, AllOrigsExtractedIndex, DoneCaseClassOfXmlFormOrigsDoneIndex, DoneOrigsExtractedIndex}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._

object JmsProcessingIndexes {
  type AllOrigsExtractedIndex = SrcId
  type DoneOrigsExtractedIndex = SrcId
  lazy val AllOrigsExtractedKey = "AllOrigsExtractedKey"

  type AllCaseClassOfXmlFormOrigsDoneIndex = SrcId
  type DoneCaseClassOfXmlFormOrigsDoneIndex = SrcId
  lazy val AllCaseClassOfXmlFormOrigsDoneKey = "AllCaseClassOfXmlFormOrigsDoneKey"
}

case class ConvertedToOrigWithMeta[Model <: Product](messageId: String, origs: List[Model])
case class ConvertedChangedOrigWithMeta[Model <: Product](changedId: String, txId: String, convertedModelClass: Class[Model], converted: List[CaseClassOfXml])

trait ProcessingAssemble[Model <: Product] extends CallerAssemble {

  // ConvertToOrigWithMeta -> ConvertedToOrigWithMeta
  // ConvertChangedOrigWithMeta -> ConvertedChangedOrigWithMeta
  def modelCl: Class[Model]

  //override def subAssembles: List[Assemble] = new CaseClassOfXmlSubAssemble[Model](modelCl) :: Nil
}

@assemble class CaseClassOfXmlSubAssemble[Orig <: Product](modelCl: Class[Orig]) extends Assemble {
  def AllOrigsExtractedJoiner(
    key: SrcId,
    @by[DoneOrigsExtractedIndex] model: Each[ConvertedToOrigWithMeta[Orig]]
  ): Values[(AllOrigsExtractedIndex, ConvertedToOrigWithMeta[Product])] = List(model.messageId -> model.asInstanceOf[ConvertedToOrigWithMeta[Product]])

  def AllCaseClassOfXmlFormOrigsDoneJoiner(
    key: SrcId,
    @by[DoneCaseClassOfXmlFormOrigsDoneIndex] model: Each[ConvertedChangedOrigWithMeta[Orig]]
  ): Values[(AllCaseClassOfXmlFormOrigsDoneIndex, ConvertedChangedOrigWithMeta[Product])] = List(model.changedId -> model.asInstanceOf[ConvertedChangedOrigWithMeta[Product]])
}