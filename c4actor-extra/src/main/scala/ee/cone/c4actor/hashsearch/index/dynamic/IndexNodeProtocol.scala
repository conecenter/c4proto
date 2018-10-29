package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor.AnyOrigProtocol.AnyOrig
import ee.cone.c4actor.ProtocolsApp
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object IndexNodeProtocol extends Protocol{

  import ee.cone.c4actor.AnyOrigProtocol._

  // A
  @Id(0x0169) case class IndexNode(
    @Id(0x016a) indexNodeId: String,
    @Id(0x016b) modelId: Int,
    @Id(0x016c) byAdapterId: Long,
    @Id(0x0187) commonPrefix: String
  )

  // B
  @Id(0x0180) case class IndexNodeSettings(
    @Id(0x0181) srcId: String,
    @Id(0x0182) allAlwaysAlive: Boolean,
    @Id(0x0195) keepAliveSeconds: Option[Long]
  )

  // Combined stats about children of one node
  @Id(0x0200) case class IndexByNodesStats(
    @Id(0x0185) srcId: String,
    @Id(0x0186) lastPongSeconds: Long,
    @Id(0x0199) aliveList: List[String]
  )

  // C
  @Id(0x0170) case class IndexByNode(
    @Id(0x0175) leafId: String,
    @Id(0x0177) indexNodeId: String,
    @Id(0x0194) modelId: Int,
    @Id(0x0173) heapIds: List[String],
    @Id(0x0204) byStr: String
  )

  @Id(0x0201) case class IndexByNodeLastSeen(
    @Id(0x0202) srcId: String,
    @Id(0x0203) lastSeenAtSeconds: Long
  )

  // E
  @Id(0x0190) case class IndexByNodeSettings(
    @Id(0x0191) srcId: String,
    @Id(0x0192) alwaysAlive: Boolean,
    @Id(0x0193) keepAliveSeconds: Option[Long]
  )

  @Id(0x0196) case class TimeMeasurement(
    @Id(0x0197) srcId: String,
    @Id(0x0198) measurement: Option[Long]
  )
}

trait WithIndexNodeProtocol extends ProtocolsApp {
  override def protocols: List[Protocol] = IndexNodeProtocol :: super.protocols
}
