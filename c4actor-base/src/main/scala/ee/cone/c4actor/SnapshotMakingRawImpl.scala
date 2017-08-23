package ee.cone.c4actor

import java.time.temporal.TemporalAmount
import java.time.{Duration, Instant}

import ee.cone.c4actor.QProtocol.{Update, Updates}
import okio.ByteString

class SnapshotMakingRawWorldFactory(adapterRegistry: QAdapterRegistry) extends RawWorldFactory {
  def create(): RawWorld = new SnapshotMakingRawWorld(adapterRegistry)
}

class SnapshotMakingRawWorld(
  qAdapterRegistry: QAdapterRegistry,
  state: Map[Update,Update] = Map.empty,
  val offset: Long = 0
) extends RawWorld {
  def reduce(data: Array[Byte], offset: Long): RawWorld = {
    val updatesAdapter = qAdapterRegistry.updatesAdapter
    val newState = (state /: updatesAdapter.decode(data).updates){(state,up)⇒
      if(up.value.size > 0) state + (up.copy(value=ByteString.EMPTY)→up)
      else state - up
    }
    new SnapshotMakingRawWorld(qAdapterRegistry,newState,offset)
  }
  def hasErrors: Boolean = false
  def save(rawSnapshot: RawSnapshot): Unit = {
    println("Saving...")
    val updates = state.values.toList.sortBy(u⇒(u.valueTypeId,u.srcId))
    val updatesAdapter = qAdapterRegistry.updatesAdapter
    rawSnapshot.save(updatesAdapter.encode(Updates("",updates)), offset)
    println("OK")
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