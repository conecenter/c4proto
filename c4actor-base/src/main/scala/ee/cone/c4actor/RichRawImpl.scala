package ee.cone.c4actor

import ee.cone.c4assemble.Types.World

import scala.collection.immutable.{Map, Seq}

class RichRawWorld(
  reducer: ReducerImpl,
  worldOpt: Option[World],
  errors: List[Exception]
) extends RawWorld {
  def world: World = worldOpt.getOrElse(reducer.createWorld(Map.empty))
  def offset: Long = reducer.qMessages.worldOffset(world)
  def reduce(data: Array[Byte], offset: Long): RawWorld = try {
    val updates = reducer.qMessages.toUpdates(data) ::: reducer.qMessages.offsetUpdate(offset)
    new RichRawWorld(reducer, Option(reducer.reduceRecover(world, updates)), errors)
  } catch {
    case e: Exception ⇒
      e.printStackTrace() // ??? exception to record
      new RichRawWorld(reducer, worldOpt, errors = e :: errors)
  }
  def hasErrors: Boolean = errors.nonEmpty
}

object WorldStats {
  def make(world: World): String = world.collect{ case (worldKey, index:Map[_,_]) ⇒
    val sz = index.values.collect { case s: Seq[_] ⇒ s.size }.sum
    s"$worldKey : ${index.size} : $sz"
  }.mkString("\n")
}

class StatsObserver(inner: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case richRawWorld: RichRawWorld ⇒
      println(WorldStats.make(richRawWorld.world))
      println("Stats OK")
      inner
  }
}

class RichRawObserver(observers: List[Observer], completing: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case richRawWorld: RichRawWorld ⇒
      val newObservers = observers.flatMap(_.activate(richRawWorld.world))
      if(newObservers.isEmpty) completing.activate(rawWorld)
      else new RichRawObserver(newObservers,completing)
  }
}
