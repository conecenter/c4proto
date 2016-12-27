package ee.cone.c4gate

import ee.cone.c4actor._

trait SSEApp extends TxTransformsApp with DataDependenciesApp {
  def sseAllowOrigin: Option[String]
  def indexFactory: IndexFactory
  //
  lazy val sseMessages = new SSEMessagesImpl(sseAllowOrigin)
  private lazy val sseTxTransform = new SSETxTransform(sseMessages)
  private lazy val httpPostByConnectionJoin = indexFactory.createJoinMapIndex(new HttpPostByConnectionJoin)
  private lazy val sseConnectionJoin = indexFactory.createJoinMapIndex(new SSEConnectionJoin)
  //
  override def txTransforms: List[TxTransform] = sseTxTransform :: super.txTransforms
  override def dataDependencies: List[DataDependencyTo[_]] =
    httpPostByConnectionJoin :: sseConnectionJoin :: super.dataDependencies
}
