package ee.cone.c4gate

import ee.cone.c4actor._

class PublishApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with TreeIndexValueMergerFactoryApp
  with PublishingApp
  with NoAssembleProfilerApp
  with FileRawSnapshotApp
{
  def mimeTypes: Map[String,String] = Map( //not finished on gate-server side
    "html" -> "text/html; charset=UTF-8",
    "js" -> "application/javascript",
    "ico" -> "image/x-icon"
  )
  def publishFromStrings: List[(String,String)] = Nil
  def txObserver = None
}
