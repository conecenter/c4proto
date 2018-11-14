package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

class PublishApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with TreeIndexValueMergerFactoryApp
  with PublishingApp
  with NoAssembleProfilerApp
  with FileRawSnapshotApp
{
  def mimeTypes: Map[String,String] = Map( //not finished on gate-server side
    "html" → "text/html; charset=UTF-8",
    "js" → "application/javascript",
    "ico" → "image/x-icon"
  )
  def publishFromStrings: List[(String,String)] = Nil
  def txObserver = None
}

trait PublishingApp extends ProtocolsApp with InitialObserversApp with GzipCompressorApp {
  def config: Config
  def qMessages: QMessages
  def idGenUtil: IdGenUtil
  def mimeTypes: Map[String,String]
  def publishFromStrings: List[(String,String)]

  private lazy val publishDir = "htdocs"
  private lazy val publishingObserver =
    new PublishingObserver(compressor,qMessages,idGenUtil,publishDir,publishFromStrings,mimeTypes.get)
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] =
    publishingObserver :: super.initialObservers
}
