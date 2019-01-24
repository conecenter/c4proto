package ee.cone.c4actor.jms_processing

import java.util.UUID

import JmsProtocol._
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.QProtocol.{Firstborn, TxRef}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.jms_processing.JmsProcessingIndexes.{AllOrigsExtractedIndex, DoneCaseClassOfXmlFormOrigsDoneIndex}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{All, Assemble, assemble, by}


trait CaseClassOfXml extends Product

trait WithConverters {
  def xmlConverter: CaseClassFromXMLConverter
  def caseClassConverters: List[ProcessingAssemble[_ <: Product]] = Nil // CaseClassFromXML.clName => converter
  def caseClassConvertersMap: Map[String, ProcessingAssemble[_ <: Product]] = caseClassConverters.map(p => (p.modelCl.getName, p)).toMap
  private val errorsList: List[SrcId] = xmlConverter.possibleClasses.map(_.getName).filterNot(caseClassConvertersMap.contains)
  if(errorsList.nonEmpty) throw new Exception(s"Not found caseClassConverters for: $errorsList")
}

trait CaseClassFromXMLConverter {
  def convert(message: JmsIncomeMessage): CaseClassOfXml
  def convert(converted: CaseClassOfXml, txId: String): JmsOutcomeMessage
  def possibleClasses: List[Class[_ <: Product]]
}

case class WatchedOrig(className: String)
object WatchedOrig {
  def apply(cl: Class[_ <: Product]): WatchedOrig = WatchedOrig(cl.getName)
}

trait WatchedOrigsApp {
  def watchedOrigs: List[WatchedOrig] = Nil
}

trait JmsProcessingApp extends WithConverters with AssemblesApp with MortalFactoryApp with UpdatesPreprocessorsApp with WatchedOrigsApp {
  override def assembles: List[Assemble] =
    new ProcessJmsMessageAssemble(xmlConverter, qAdapterRegistry, jmsSender) ::
    mortal(classOf[JmsIncomeDoneMarker]) ::
    caseClassConverters :::
    caseClassConverters.map(p => new CaseClassOfXmlSubAssemble(p.modelCl)) :::
    super.assembles

  def jmsSender: JmsSender

  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def processors: List[UpdatesPreprocessor] = new OnChangeOrigsProcessor(watchedOrigs, toUpdate, qAdapterRegistry) :: super.processors
}

case class ProcessChangedTransform(changedId: String, changed: List[ConvertedChangedOrigWithMeta[Product]], converter: CaseClassFromXMLConverter) extends TxTransform{
    def transform(local: Context): Context = {
    val messages: List[JmsOutcomeMessage] = changed.flatMap(ch => {
      val txId = ch.txId
      ch.converted.map(p => converter.convert(p, txId))
    })
    val marker = ChangedProcessedMarker(changedId)
    TxAdd((messages :+ marker).flatMap(LEvent.update))(local)
  }
}

case class ProcessConvertedOrigs(messageId: String, allOrigs: List[ConvertedToOrigWithMeta[Product]]) extends TxTransform {
  def transform(local: Context): Context = {
    TxAdd((allOrigs.flatMap(_.origs) :+ JmsIncomeDoneMarker(messageId)).flatMap(LEvent.update))(local)
  }
}

case class SendJmsMessageTransform(message: JmsOutcomeMessage, jmsSender: JmsSender) extends TxTransform {
  def transform(local: Context): Context = {
    if(jmsSender.sendMessage(message))
      TxAdd(LEvent.update(JmsOutcomeDoneMarker(message.messageId)))(local)
    else local
  }
}

case class ConvertToOrigWithMeta(messageId: String, toConvert: CaseClassOfXml)

case class ConvertChangedOrigWithMeta(changedId: String, txId: String, changed: ModelsChanged)

class ProcessJmsMessageAssemble(converter: CaseClassFromXMLConverter, qAdapterRegistry: QAdapterRegistry, jmsSender: JmsSender) extends Assemble {
  def ProcessJoiner(
    key: String,
    message: Each[JmsIncomeMessage],
    markers: Values[JmsIncomeDoneMarker]
  ): Values[(SrcId, ConvertToOrigWithMeta)] =
    if(markers.isEmpty) {
      val convertedMessage = converter.convert(message)
      List(WithPK(ConvertToOrigWithMeta(message.messageId, convertedMessage)))
    } else Nil

  def AliveMarkerJoiner(
    key: SrcId,
    message: Each[JmsIncomeMessage],
    marker: Each[JmsIncomeDoneMarker]
  ): Values[(Alive, JmsIncomeDoneMarker)] = List(WithPK(marker))

  def AllOrigsExtractedToTransformJoiner(
    key: SrcId,
    @by[AllOrigsExtractedIndex] models: Values[ConvertedToOrigWithMeta[Product]]
  ): Values[(SrcId, TxTransform)] = List(WithPK(ProcessConvertedOrigs(key, models.toList)))

///////////////////////////////////////////////////////////////////////////////////////

  def ChangedWithTxRefJoiner(
    key: SrcId,
    changed: Each[ModelsChanged],
    txRef: Each[TxRef],
    markers: Values[ChangedProcessedMarker]
  ): Values[(SrcId, ConvertChangedOrigWithMeta)] = {
    if(markers.isEmpty) {
      List(WithPK(ConvertChangedOrigWithMeta(changed.srcId, txRef.txId, changed)))
    }
    else Nil
  }

  def AllCaseClassOfXmlFormOrigsDoneJoiner(
    key: SrcId,
    @by[DoneCaseClassOfXmlFormOrigsDoneIndex] converted: Values[ConvertedChangedOrigWithMeta[Product]]
  ): Values[(SrcId, TxTransform)] = List(WithPK(ProcessChangedTransform(key, converted.toList, converter)))


  ////////////////////////////////////////////////////////////////////////////////////

  def OutComeMessagesToAll(
    key: SrcId,
    message: Each[JmsOutcomeMessage]
  ): Values[(All, JmsOutcomeMessage)] = List(All -> message)

  def ToSendOutcomeTransform(
    key: SrcId,
    firstborn: Values[Firstborn],
    @by[All] messages: Values[JmsOutcomeMessage],
    @by[All] messagesDone: Values[JmsOutcomeDoneMarker]
  ): Values[(SrcId, TxTransform)] = {
    List(WithPK(SendJmsMessageTransform(messages.filterNot(
      m => messagesDone.exists(_.srcId == m.messageId)).minBy(_.txId), jmsSender)))
  }

}
