package ee.cone.c4actor.jms_processing

import JmsProtocol.{ChangedProcessedMarker, JmsIncomeDoneMarker, JmsIncomeMessage, ModelsChanged}
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}


trait ConvertedOrig extends Product

trait WithConverters {
  def xmlConverter: CaseClassFromXMLConverter
  def caseClassConverters: List[CaseClassToOrigsConverter] = Nil // CaseClassFromXML.clName => converter
  def caseClassConvertersMap: Map[String, CaseClassToOrigsConverter] = caseClassConverters.map(p => (p.cl.getName, p)).toMap
  private val errorsList: List[SrcId] = xmlConverter.possibleClasses.map(_.getName).filterNot(caseClassConvertersMap.contains)
  if(errorsList.nonEmpty) throw new Exception(s"Not found caseClassConverters for: $errorsList")
}

trait CaseClassFromXMLConverter {
  def convert(message: JmsIncomeMessage): ConvertedOrig
  def convert(message: ConvertedOrig): JmsIncomeMessage
  def possibleClasses: List[Class[_ <: Product]]
}

trait CaseClassToOrigsConverter {
  def cl: Class[_ <: Product]
  def convert(caseClass: ConvertedOrig): Context => List[Product]
  def convert(origs: List[Product]): Context => List[ConvertedOrig]
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
    new ProcessJmsMessageAssemble(xmlConverter, caseClassConvertersMap) ::
    mortal(classOf[JmsIncomeDoneMarker]) ::
    super.assembles

  def toUpdate: ToUpdate
  def qAdapterRegistry: QAdapterRegistry

  override def processors: List[UpdatesPreprocessor] = new OnChangeOrigsProcessor(watchedOrigs, toUpdate, qAdapterRegistry) :: super.processors
}

case class ProcessJmsMessageTxTransform(message: JmsIncomeMessage, converter: CaseClassFromXMLConverter, caseClassConvertersMap: Map[String, CaseClassToOrigsConverter]) extends TxTransform {
  def transform(local: Context): Context = {

    val converted: ConvertedOrig = converter.convert(message)
    val origs = caseClassConvertersMap.getOrElse(converted.getClass.getName, throw new Exception(s"No CaseClassToOrigsConverter for type: ${converted.getClass.getName}")).convert(converted)(local)

    val marker = JmsIncomeDoneMarker(message.messageId)
    TxAdd(LEvent.update(marker) ++ origs.flatMap(LEvent.update))(local)
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

@assemble class ProcessJmsMessageAssemble(converter: CaseClassFromXMLConverter, caseClassConvertersMap: Map[String, CaseClassToOrigsConverter]) extends Assemble {
  def ProcessJoiner(
    key: String,
    message: Each[JmsIncomeMessage],
    markers: Values[JmsIncomeDoneMarker]
  ): Values[(SrcId, TxTransform)] =
    if(markers.isEmpty) WithPK(ProcessJmsMessageTxTransform(message, converter, caseClassConvertersMap)) :: Nil else Nil

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

}
