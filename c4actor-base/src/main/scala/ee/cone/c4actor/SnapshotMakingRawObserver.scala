package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.{Update, Updates}
import okio.ByteString

class SnapshotMakingRawObserver(
  qAdapterRegistry: QAdapterRegistry,
  rawSnapshot: RawSnapshot,
  state: Map[Update,Update] = Map.empty,
  val offset: Long = 0,
  time: Option[Long] = Option(0L)
) extends RawObserver {
  private def updatesAdapter = qAdapterRegistry.updatesAdapter
  private def copy(
    state: Map[Update,Update] = state,
    offset: Long = offset,
    time: Option[Long] = time
  ): RawObserver =
    new SnapshotMakingRawObserver(qAdapterRegistry,rawSnapshot,state,offset,time)
  def reduce(data: Array[Byte], offset: Long): RawObserver = {
    val newState = (state /: updatesAdapter.decode(data).updates){(state,up)⇒
      if(up.value.size > 0) state + (up.copy(value=ByteString.EMPTY)→up)
      else state - up
    }
    copy(offset=offset,state=newState)
  }
  def hasErrors: Boolean = false
  def activate(fresh: () ⇒ RawObserver, endOffset: Long): RawObserver =
    time.map{ until ⇒
      if (offset < endOffset) {
        val now = System.currentTimeMillis
        if(now < until) this else {
          println(s"loaded $offset/$endOffset")
          copy(time = Option(now+1000))
        }
      } else {
        println("Saving...")
        val updates = state.values.toList.sortBy(u⇒(u.valueTypeId,u.srcId))
        rawSnapshot.save(updatesAdapter.encode(Updates("",updates)),offset)
        println("OK")
        copy(time = None)
      }
    }.getOrElse(this)
  def isActive: Boolean = time.nonEmpty
}
