package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Firstborn, Update, Updates}
import ee.cone.c4actor.Types.{NextOffset, SharedComponentMap}
import ee.cone.c4assemble.Single
import ee.cone.c4assemble.Types._

import scala.collection.immutable.{Map, Seq}

object Merge {
  def apply[A](path: List[Any], values: List[A]): A =
    if(values.size <= 1) Single(values)
    else {
      val maps = values.collect{ case m: Map[_,_] ⇒ m.toList }
      assert(values.size == maps.size, s"can not merge $values of $path")
      maps.flatten
        .groupBy(_._1).transform((k,kvs)⇒Merge(k :: path, kvs.map(_._2)))
            .asInstanceOf[A]
    }
}

class RichRawWorldFactory(
  toInjects: List[ToInject], toUpdate: ToUpdate, actorName: String
) extends RawWorldFactory {
  def create(updates: Updates): RichRawWorld = {
    val injectedList = for(toInject ← toInjects; injected ← toInject.toInject)
      yield Map(injected.pair)
    val empty = new RichRawWorld(Merge(Nil,injectedList), emptyReadModel, updates.srcId, Nil)
    val firstborn = LEvent.update(Firstborn(actorName)).toList.map(toUpdate.toUpdate)
    val assembled = ReadModelAddKey.of(empty)(firstborn,empty)
    new RichRawWorld(empty.injected, assembled, empty.offset, Nil).reduce(List(updates))
  }
}

class RichRawWorld(
  val injected: SharedComponentMap,
  val assembled: ReadModel,
  val offset: NextOffset,
  errors: List[Exception]
) extends RawWorld with RichContext with LazyLogging {
  def reduce(events: List[Updates]): RichRawWorld = if(events.isEmpty) this else try {
    val updates: List[Update] = events.flatMap(updates ⇒ updates.updates)
    val nAssembled = ReadModelAddKey.of(this)(updates,this)
    new RichRawWorld(injected, nAssembled, events.last.srcId, errors)
  } catch {
    case e: Exception ⇒
      logger.error("reduce", e) // ??? exception to record
      if(events.size == 1)
        new RichRawWorld(
          injected, assembled, Single(events).srcId, errors = e :: errors
        )
      else {
        val(a,b) = events.splitAt(events.size / 2)
        reduce(a).reduce(b)
      }
  }
  def hasErrors: Boolean = errors.nonEmpty
}

object WorldStats {
  def make(context: AssembledContext): String = context.assembled.collect {
    case (worldKey, index: Map[_, _]) ⇒
      val sz = index.values.collect { case s: Seq[_] ⇒ s.size }.sum
      s"$worldKey : ${index.size} : $sz"
  }.mkString("\n")
}

class StatsObserver(inner: RawObserver) extends RawObserver with LazyLogging {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case richRawWorld: RichRawWorld ⇒
      logger.debug(WorldStats.make(richRawWorld))
      logger.info("Stats OK")
      inner
  }
}

class RichRawObserver(
  observers: List[Observer],
  completing: RawObserver
) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case richRawWorld: RichRawWorld ⇒
      val newObservers = observers.flatMap(_.activate(richRawWorld))
      if(newObservers.isEmpty) completing.activate(rawWorld)
      else new RichRawObserver(newObservers, completing)
  }
}
