package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.{Update, Updates}
import okio.ByteString

class SnapshotMakingRawWorld(
  val qAdapterRegistry: QAdapterRegistry,
  val state: Map[Update,Update] = Map.empty,
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
}

class SnapshotMakingRawObserver(rawSnapshot: RawSnapshot, completing: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case world: SnapshotMakingRawWorld ⇒
    println("Saving...")
    val updates = world.state.values.toList.sortBy(u⇒(u.valueTypeId,u.srcId))
    val updatesAdapter = world.qAdapterRegistry.updatesAdapter
    rawSnapshot.save(updatesAdapter.encode(Updates("",updates)), world.offset)
    println("OK")
    completing.activate(rawWorld)
  }
}
