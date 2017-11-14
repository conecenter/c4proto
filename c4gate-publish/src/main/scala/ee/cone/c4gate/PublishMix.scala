package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol
import ee.cone.c4assemble._

class PublishApp extends ServerApp
  with `The EnvConfigImpl` with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with TreeIndexValueMergerFactoryApp
  with PublishingApp
  with `The NoAssembleProfiler`
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

trait PublishingApp extends `The HttpProtocol` with InitialObserversApp {
  def `the QMessages`: QMessages
  def mimeTypes: Map[String,String]
  def publishFromStrings: List[(String,String)]

  private lazy val publishDir = "htdocs"
  private lazy val publishingObserver =
    new PublishingObserver(`the QMessages`,publishDir,publishFromStrings,mimeTypes.get)
  override def initialObservers: List[Observer] =
    publishingObserver :: super.initialObservers
}
