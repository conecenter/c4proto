package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import okio.ByteString

class SnapshotMakingRawObserver(
  qMessages: QMessages,
  val offset: Long = 0,
  state: Map[Update,Update] = Map.empty,
  time: Long = 0,
  val isActive: Boolean = true
) extends RawObserver {
  private def copy(offset: Long = offset, state: Map[Update,Update] = state): RawObserver = ???
  def reduce(data: Array[Byte], offset: Long): RawObserver = {
    val newState = (state /: qMessages.toUpdates(data)){(state,up)⇒
      if(up.value.size > 0) state + (up.copy(value=ByteString.EMPTY)→up)
      else state - up
    }
    copy(offset=offset,state=newState)
  }
  def hasErrors: Boolean = false
  def activate(fresh: () ⇒ RawObserver, endOffset: Long): RawObserver = {
    if (offset < endOffset) {
      val now = System.currentTimeMillis
      if(now < until) this else {
        println(s"loaded $offset/$endOffset")
        copy(time = now+1000)
      }
    } else {
      println(WorldStats.make(world))
      println("Stats OK")
      copy(time = None)
    }


  }
}
/*
class RichRawObserver(
  reducer: ReducerImpl,
  observers: List[Observer] = Nil,
  worldOpt: Option[World] = None,
  errors: List[Exception] = Nil,
  time: Option[Long] = Option(0L)
) extends RawObserver {
  private def copy(
    observers: List[Observer] = observers,
    worldOpt: Option[World] = worldOpt,
    errors: List[Exception] = errors,
    time: Option[Long] = time
  ) = new RichRawObserver(reducer, observers, worldOpt, errors)
  private def world = worldOpt.getOrElse(reducer.createWorld(Map.empty))
  def offset: Long = reducer.qMessages.worldOffset(world)
  def reduce(data: Array[Byte], offset: Long): RawObserver = try {
    val updates = reducer.qMessages.toUpdates(data) ::: reducer.qMessages.offsetUpdate(offset)
    copy(worldOpt = Option(reducer.reduceRecover(world, updates)))
  } catch {
    case e: Exception ⇒
      e.printStackTrace() // ??? exception to record
      copy(errors = e :: errors)
  }
  def activate(fresh: ()⇒RawObserver, endOffset: Long): RawObserver = {
    time.map{ until ⇒
      if (offset < endOffset) {
        val now = System.currentTimeMillis
        if(now < until) this else {
          println(s"loaded $offset/$endOffset")
          copy(time = Option(now+1000))
        }
      } else {
        println(WorldStats.make(world))
        println("Stats OK")
        copy(time = None)
      }
    }.getOrElse{
      val ctx = new ObserverContext(() ⇒ fresh() match { case o: RichRawObserver ⇒ o.world })
      copy(observers = observers.flatMap(_.activate(ctx)))
    }
  }
  def hasErrors: Boolean = errors.nonEmpty
  def isActive: Boolean = observers.nonEmpty
}*/

case class Timer(until: Long){
  def activate() =

}