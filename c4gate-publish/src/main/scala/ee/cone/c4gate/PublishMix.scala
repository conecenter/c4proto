package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class PublishApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with InitLocalsApp
  with PublishingApp
{
  def mimeTypes: Map[String,String] = Map( //not finished on gate-server side
    "html" → "text/html; charset=UTF-8",
    "js" → "application/javascript",
    "ico" → "image/x-icon"
  )
  def txObserver = None
  def doAfterPublish(): Unit =
    if(config.get("C4PUBLISH_THEN_EXIT").nonEmpty) Future{ System.exit(0) }
}

trait PublishingApp extends ProtocolsApp with InitialObserversApp {
  def config: Config
  def qMessages: QMessages
  def qReducer: Reducer
  def mimeTypes: Map[String,String]

  private lazy val publishThenExit = config.get("C4PUBLISH_THEN_EXIT").nonEmpty
  private lazy val publishDir = config.get("C4PUBLISH_DIR")
  private lazy val publishingObserver =
    new PublishingObserver(qMessages,qReducer,publishDir,mimeTypes.get,publishThenExit)
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] =
    publishingObserver :: super.initialObservers
}
