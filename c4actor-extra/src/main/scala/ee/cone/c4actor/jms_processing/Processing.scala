package ee.cone.c4actor.jms_processing

import JmsProtocol.{ChangedProcessedMarker, JmsIncomeDoneMarker, JmsIncomeMessage, ModelsChanged}
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.jms_processing.JmsProcessingIndexes.AllOrigsExtractedIndex
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}


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
  def convert(message: CaseClassOfXml): JmsIncomeMessage
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
    new ProcessJmsMessageAssemble(xmlConverter) ::
    mortal(classOf[JmsIncomeDoneMarker]) ::
    caseClassConverters :::
    super.assembles

  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def processors: List[UpdatesPreprocessor] = new OnChangeOrigsProcessor(watchedOrigs, toUpdate, qAdapterRegistry) :: super.processors
}

case class ProcessJmsMessageTxTransform(message: JmsIncomeMessage, converter: CaseClassFromXMLConverter) extends TxTransform {
  def transform(local: Context): Context = {

    val converted: CaseClassOfXml = converter.convert(message)
    //val origs = caseClassConvertersMap.getOrElse(converted.getClass.getName, throw new Exception(s"No CaseClassToOrigsConverter for type: ${converted.getClass.getName}")).convert(converted)(local)
    val marker = JmsIncomeDoneMarker(message.messageId)
    TxAdd(LEvent.update(marker) ++ LEvent.update(converted))(local)
  }
}

case class ProcessChangedOrigsJoiner(changed: ModelsChanged, qAdapterRegistry: QAdapterRegistry) extends TxTransform{
  def transform(local: Context): Context = {

    val models = changed.changedOrigs
    val origs: List[Product] = models.flatMap(m => ByPrimaryKeyGetter[Product](qAdapterRegistry.byId(m.valueTypeId).className).of(local).get(m.srcId).toList)

    val marker = ChangedProcessedMarker(changed.srcId)
    // TODO
    local
  }
}

case class ProcessConvertedOrigs(allOrigs: List[Product]) extends TxTransform {
  def transform(local: Context): Context = {
    TxAdd(allOrigs.flatMap(LEvent.update))(local)
  }
}

class ProcessJmsMessageAssemble(converter: CaseClassFromXMLConverter) extends Assemble {
  def ProcessJoiner(
    key: String,
    message: Each[JmsIncomeMessage],
    markers: Values[JmsIncomeDoneMarker]
  ): Values[(SrcId, TxTransform)] =
    if(markers.isEmpty) WithPK(ProcessJmsMessageTxTransform(message, converter)) :: Nil else Nil

  def AliveMarkerJoiner(
    key: SrcId,
    message: Each[JmsIncomeMessage],
    marker: Each[JmsIncomeDoneMarker]
  ): Values[(Alive, JmsIncomeDoneMarker)] = List(WithPK(marker))

  def ChangedWithTxRefJoiner(
    key: SrcId,
    changed: Each[ModelsChanged],
    markers: Values[ChangedProcessedMarker]
  ): Values[(SrcId, TxTransform)] = {
    if(markers.isEmpty) Nil
    else Nil
  }

  def AllOrigsExtractedToTransformJoiner(
    key: SrcId,
    @by[AllOrigsExtractedIndex] models: Values[Product]
  ): Values[(SrcId, TxTransform)] = List(WithPK(ProcessConvertedOrigs(models.toList)))

}
