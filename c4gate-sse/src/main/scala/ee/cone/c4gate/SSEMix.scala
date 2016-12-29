package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

trait SSEApp extends DataDependenciesApp with ProtocolsApp {
  def indexFactory: IndexFactory
  def sseUI: SSEui
  //
  private lazy val httpPostByConnectionJoin = indexFactory.createJoinMapIndex(new HttpPostByConnectionJoin)
  private lazy val sseConnectionJoin = indexFactory.createJoinMapIndex(new SSEConnectionJoin(sseUI))
  override def dataDependencies: List[DataDependencyTo[_]] =
    httpPostByConnectionJoin :: sseConnectionJoin :: super.dataDependencies
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
}
