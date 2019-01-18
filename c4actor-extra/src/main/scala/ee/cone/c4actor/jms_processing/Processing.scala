package ee.cone.c4actor.jms_processing

import ee.cone.c4actor.JmsProtocol.{JmsDoneMarker, JmsMessage}
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}


trait CaseClassFromXML extends Product

trait WithConverters {
  def xmlConverter: CaseClassFromXMLConverter
  def caseClassConverters: List[CaseClassToOrigsConverter] = Nil // CaseClassFromXML.clName => converter
  def caseClassConvertersMap: Map[String, CaseClassToOrigsConverter] = caseClassConverters.map(p => (p.cl.getName, p)).toMap
  private val errorsList: List[SrcId] = xmlConverter.possibleClasses.map(_.getName).filterNot(caseClassConvertersMap.contains)
  if(errorsList.nonEmpty) throw new Exception(s"Not found caseClassConverters for: $errorsList")
}

trait CaseClassFromXMLConverter {
  def convert(message: JmsMessage): CaseClassFromXML
  def possibleClasses: List[Class[_ <: Product]]
}

trait CaseClassToOrigsConverter {
  def cl: Class[_ <: Product]
  def convert(caseClass: CaseClassFromXML): Context => List[Product]
}

trait JmsProcessingApp extends WithConverters with AssemblesApp with MortalFactoryApp{
  override def assembles: List[Assemble] =
    new ProcessJmsMessageAssemble(xmlConverter, caseClassConvertersMap) ::
    mortal(classOf[JmsDoneMarker]) ::
    super.assembles
}

case class ProcessJmsMessageTxTransform(message: JmsMessage, converter: CaseClassFromXMLConverter, caseClassConvertersMap: Map[String, CaseClassToOrigsConverter]) extends TxTransform {
  def transform(local: Context): Context = {

    val converted: CaseClassFromXML = converter.convert(message)
    val origs = caseClassConvertersMap.getOrElse(converted.getClass.getName, throw new Exception(s"No CaseClassToOrigsConverter for type: ${converted.getClass.getName}")).convert(converted)(local)

    val marker = JmsDoneMarker(message.messageId)
    TxAdd(LEvent.update(marker) ++ origs.flatMap(LEvent.update))(local)
  }
}

@assemble class ProcessJmsMessageAssemble(converter: CaseClassFromXMLConverter, caseClassConvertersMap: Map[String, CaseClassToOrigsConverter]) extends Assemble {
  def ProcessJoiner(
    key: String,
    message: Each[JmsMessage],
    markers: Values[JmsDoneMarker]
  ): Values[(SrcId, TxTransform)] =
    if(markers.isEmpty) WithPK(ProcessJmsMessageTxTransform(message, converter, caseClassConvertersMap)) :: Nil else Nil

  def AliveMarkerJoiner(
    key: SrcId,
    message: Each[JmsMessage],
    marker: Each[JmsDoneMarker]
  ): Values[(Alive, JmsDoneMarker)] = List(WithPK(marker))
}
