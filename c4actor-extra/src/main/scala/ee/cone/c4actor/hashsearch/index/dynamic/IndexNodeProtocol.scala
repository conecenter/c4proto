package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4actor.ProtocolsApp
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object IndexNodeProtocol extends Protocol{

  import ee.cone.c4actor.AnyProtocol._

  @Id(0x0169) case class IndexNode(
    @Id(0x016a) srcId: String,
    @Id(0x016b) modelClName: String,
    @Id(0x016c) byClName: String,
    @Id(0x016d) allAlwaysAlive: Boolean
    //@Id(0x016e) keepAliveSeconds: Option[Long]
  )

  @Id(0x0170) case class IndexByNode(
    @Id(0x0175) srcId: String,
    @Id(0x0177) modelClName: String,
    @Id(0x0174) byClName: String,
    @Id(0x0171) alwaysAlive: Boolean,
    @Id(0x0176) creationTimeSeconds: Long,
    @Id(0x0172) keepAliveSeconds: Option[Long],
    @Id(0x0173) byInstance: Option[AnyObject]
  )

}

trait WithIndexNodeProtocol extends ProtocolsApp {
  override def protocols: List[Protocol] = IndexNodeProtocol :: super.protocols
}
