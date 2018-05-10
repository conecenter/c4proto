package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4actor.ProtocolsApp
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object IndexNodeProtocol extends Protocol{

  import ee.cone.c4actor.AnyProtocol._

  // A
  @Id(0x0169) case class IndexNode(
    @Id(0x016a) srcId: String,
    @Id(0x016b) modelId: Int,
    @Id(0x016c) byAdapterId: Long,
    @Id(0x0187) lensName: List[String]
  )

  // B
  @Id(0x0180) case class IndexNodeSetting(
    @Id(0x0181) srcId: String,
    @Id(0x0182) allAlwaysAlive: Boolean,
    @Id(0x0183) keepAliveSeconds: Option[Long]
  )

  // C
  @Id(0x0170) case class IndexByNode(
    @Id(0x0175) srcId: String,
    @Id(0x0177) indexNodeId: String,
    @Id(0x0173) byInstance: Option[AnyObject]
  )

  // D
  @Id(0x0184) case class IndexByNodeStats(
    @Id(0x185) srcId: String,
    @Id(0x186) lastPongSeconds: Long
  )

  // E
  @Id(0x0190) case class IndexByNodeSetting(
    @Id(0x0191) srcId: String,
    @Id(0x0192) alwaysAlive: Boolean,
    @Id(0x0193) keepAliveSeconds: Option[Long]
  )



  // Data - C orig (TODO in rich isAlive: Boolean) form D, D - stats (usage) update every pong , E - custom setting (Always lives)
  // TODO C,D clean up after week or so if no D.rich.notActive, E
  // B,E creation only by hand
  // A, C creation on demand
  // if not B, E, default keepAlive 60*5 seconds

  // C,D one object
  // Refresh rate: 60 s, test rate 1 s
}

trait WithIndexNodeProtocol extends ProtocolsApp {
  override def protocols: List[Protocol] = IndexNodeProtocol :: super.protocols
}
