package ee.cone.c4gate

import ee.cone.c4actor._

trait PublishingApp extends InitialObserversApp with HttpProtocolApp {
  def config: Config
  def qMessages: QMessages
  def idGenUtil: IdGenUtil
  def mimeTypes: Map[String,String]
  def publishFromStrings: List[(String,String)]

  private lazy val publishDir = "htdocs"
  private lazy val publishingObserver =
    new PublishingObserver(GzipFullCompressor(),qMessages,idGenUtil,publishDir,publishFromStrings,mimeTypes.get)
  override def initialObservers: List[Observer[RichContext]] =
    publishingObserver :: super.initialObservers
}
