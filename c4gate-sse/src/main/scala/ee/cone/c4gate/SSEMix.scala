package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4proto.Protocol

trait SSEApp extends DataDependenciesApp with ProtocolsApp {
  def indexFactory: IndexFactory
  def sseUI: SSEui
  //
  override def dataDependencies: List[DataDependencyTo[_]] =
    indexFactory.createJoinMapIndex(new HttpPostByConnectionJoin) ::
    indexFactory.createJoinMapIndex(new SSEConnectionJoin(sseUI)) ::
    super.dataDependencies
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
}
