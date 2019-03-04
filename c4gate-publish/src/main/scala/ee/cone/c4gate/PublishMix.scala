package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

trait PublishingApp extends ProtocolsApp with InitialObserversApp {
  def config: Config
  def qMessages: QMessages
  def idGenUtil: IdGenUtil
  def mimeTypes: Map[String,String]
  def publishFromStrings: List[(String,String)]

  private lazy val publishDir = "htdocs"
  private lazy val publishingObserver =
    new PublishingObserver(GzipFullCompressor(),qMessages,idGenUtil,publishDir,publishFromStrings,mimeTypes.get)
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
  override def initialObservers: List[Observer] =
    publishingObserver :: super.initialObservers
}
