package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Firstborn, Update, Updates}
import ee.cone.c4actor.Types.{NextOffset, SharedComponentMap}
import ee.cone.c4assemble.Single
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.ToByteString

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
  def create(): RichRawWorld = {
    val injectedList = for(toInject ← toInjects; injected ← toInject.toInject)
      yield Map(injected.pair)
    val eWorld = new RichRawWorld(Merge(Nil,injectedList), emptyReadModel, "0" * OffsetHexSize())
    val firstborn = LEvent.update(Firstborn(actorName)).toList.map(toUpdate.toUpdate)
    val firstRawEvent = RawEvent(eWorld.offset, ToByteString(toUpdate.toBytes(firstborn)))
    eWorld.reduce(List(firstRawEvent))
  }
}

class RichRawWorld(
  val injected: SharedComponentMap,
  val assembled: ReadModel,
  val offset: NextOffset
) extends RawWorld with RichContext with LazyLogging {
  def reduce(events: List[RawEvent]): RichRawWorld =
    if(events.isEmpty) this else {
      val nAssembled = ReadModelAddKey.of(this)(this)(events)(assembled)
      new RichRawWorld(injected, nAssembled, events.last.srcId)
    }
  def hasErrors: Boolean = ByPK(classOf[FailedUpdates]).of(this).nonEmpty
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
