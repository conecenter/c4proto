package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.N_UpdateFrom
import ee.cone.c4actor.Types.{SrcId, TypeId}
import ee.cone.c4actor._
import ee.cone.c4di.c4
import ee.cone.c4gate_devel.OrigStatReplay.S_ExternalMarker
import ee.cone.c4proto.{Id, ProtoAdapter, protocol}

import scala.annotation.tailrec

@c4("OrigStatReplayApp") final class OrigStatReplay(
  toUpdate: ToUpdate, consuming: Consuming, snapshotLast: SnapshotLast, snapshotUtil: SnapshotUtil,
  origStatReplayStats: OrigStatReplayStats,
) extends Executable with LazyLogging {
  def run(): Unit = {
    val Some(snapshotInfo) = snapshotLast.get.flatMap(snapshotUtil.hashFromName)
    consuming.process(snapshotInfo.offset, consumer => { // excluding C4REPLAY_SNAPSHOT and C4REPLAY_UNTIL
      val endOffset = consumer.endOffset
      @tailrec def iteration(): Unit = {
        val Seq(ev) = consumer.poll()
        val updates: List[N_UpdateFrom] = toUpdate.toUpdates(List(ev),"stat")
        updates.foldLeft(Map.empty[Long,(Long,Long)].withDefault(_=>(0L,0L))){ (r,el) =>
          val k = el.valueTypeId
          val (wasCount, wasSize) =  r(k)
          r.updated(k, (wasCount+1L, wasSize+el.value.size))
        }.toSeq.sorted.foreach{ case (valueTypeId, (count, size)) =>
          logger.info(s"t:${java.lang.Long.toHexString(valueTypeId)} c:$count s:$size")
        }
        origStatReplayStats.handle(updates)
        if(ev.srcId<endOffset) iteration()
      }
      iteration()
    })
  }
}

@c4("OrigStatReplayApp") final class OrigStatReplayStats(
  qAdapterRegistry: QAdapterRegistry,
  valueTypeId: Long = 0x36eb,
)(
  adapter: ProtoAdapter[Product] = qAdapterRegistry.byId(valueTypeId)
) extends LazyLogging {
  def handle(updates: List[N_UpdateFrom]): Unit =
    updates.collect { case v if v.valueTypeId == valueTypeId => adapter.decode(v.value) match {
      case p: S_ExternalMarker => p.valueTypeId
    }}.groupMapReduce(i=>i)(_=>1)((a,b)=>a+b).toSeq.sorted.foreach{ case (id, count) =>
      logger.info(s"st:${java.lang.Long.toHexString(id)} c:$count")
    }
}

@protocol("OrigStatReplayApp") object OrigStatReplay {
  @Id(0x36eb) case class S_ExternalMarker(
    @Id(0x36ec) externalMarkerId: SrcId,
    @Id(0x36ee) valueTypeId: TypeId,
  )
}

@c4("OrigStatReplayApp") final class TheUpdateCompressionMinSize extends UpdateCompressionMinSize(0L)
