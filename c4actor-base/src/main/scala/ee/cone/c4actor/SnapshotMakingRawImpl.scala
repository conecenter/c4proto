package ee.cone.c4actor

import java.time.temporal.TemporalAmount
import java.time.{Duration, Instant}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Update, Updates}
import okio.ByteString
import scala.annotation.tailrec

class SnapshotMakingRawWorldFactory(adapterRegistry: QAdapterRegistry, config: SnapshotConfig) extends RawWorldFactory {
  def create(): RawWorld = new SnapshotMakingRawWorld(config.ignore,adapterRegistry)
}

class SnapshotMakingRawWorld(
  ignore: Set[Long],
  qAdapterRegistry: QAdapterRegistry,
  state: Map[Update,Update] = Map.empty,
  val offset: Long = 0
) extends RawWorld with LazyLogging {
  def reduce(data: Array[Byte], offset: Long): RawWorld = {
    val updatesAdapter = qAdapterRegistry.updatesAdapter
    val newState = (state /: updatesAdapter.decode(data).updates){(state,up)⇒
      if(ignore(up.valueTypeId)) state
      else if(up.value.size > 0) state + (up.copy(value=ByteString.EMPTY)→up)
      else state - up
    }
    new SnapshotMakingRawWorld(ignore,qAdapterRegistry,newState,offset)
  }
  def hasErrors: Boolean = false

  @tailrec private def makeStatLine(
    currType: Long, currCount: Long, currSize: Long, updates: List[Update]
  ): List[Update] =
    if(updates.isEmpty || currType != updates.head.valueTypeId) {
      logger.info(s"t:${java.lang.Long.toHexString(currType)} c:$currCount s:$currSize")
      updates
    } else makeStatLine(currType,currCount+1,currSize+updates.head.value.size(),updates.tail)
  @tailrec private def makeStats(updates: List[Update]): Unit =
    if(updates.nonEmpty) makeStats(makeStatLine(updates.head.valueTypeId,0,0,updates))

  def save(rawSnapshot: RawSnapshot): Unit = {
    logger.info("Saving...")
    val updates = state.values.toList.sortBy(u⇒(u.valueTypeId,u.srcId))
    makeStats(updates)
    val updatesAdapter = qAdapterRegistry.updatesAdapter
    rawSnapshot.save(updatesAdapter.encode(Updates("",updates)), offset)
    logger.info("OK")
  }
}

class OnceSnapshotMakingRawObserver(rawSnapshot: RawSnapshot, completing: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case world: SnapshotMakingRawWorld ⇒
    world.save(rawSnapshot)
    completing.activate(rawWorld)
  }
}

class PeriodicSnapshotMakingRawObserver(
  rawSnapshot: RawSnapshot, period: TemporalAmount, until: Instant=Instant.MIN
) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case world: SnapshotMakingRawWorld ⇒
      if(Instant.now.isBefore(until)) this else {
        world.save(rawSnapshot)
        new PeriodicSnapshotMakingRawObserver(rawSnapshot, period, Instant.now.plus(period))
      }
  }
}