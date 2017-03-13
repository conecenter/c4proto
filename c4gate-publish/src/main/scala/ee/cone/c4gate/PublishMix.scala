package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

/*ServerApp
  with EnvConfigApp
  with KafkaProducerApp
  with InitLocalsApp with ParallelObserversApp*/
/*
  def mimeTypes: List[(String,String)] = List( //not finished on gate-server side
    "html" → "text/html; charset=UTF-8",
    "js" → "application/javascript",
    "ico" → "image/x-icon"
  )*/

trait PublishApp extends ProtocolsApp with InitialObserversApp
{
  def config: Config
  def qMessages: QMessages
  def qReducer: Reducer

  private lazy val publishMimeTypes = config.get("C4PUBLISH_MIME")
  private lazy val publishDir = config.get("C4PUBLISH_DIR")
  private lazy val publishingObserver =
    new PublishingObserver(qMessages,qReducer,publishDir,publishMimeTypes)
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] =
    publishingObserver :: super.initialObservers
}
