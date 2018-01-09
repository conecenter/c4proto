package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Firstborn, Update}
import ee.cone.c4assemble.Single

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

class ContextFactory(toInjects: List[ToInject]) {
  def create(): Context = {
    val injected = for(toInject ← toInjects; injected ← toInject.toInject)
      yield Map(injected.pair)
    new Context(Merge(Nil,injected), Map.empty, Map.empty)
  }
}

class RichRawWorldFactory(
  contextFactory: ContextFactory, toUpdate: ToUpdate, actorName: String
) extends RawWorldFactory {
  def create(): RawWorld = {
    val context = contextFactory.create()
    val updates = LEvent.update(Firstborn(actorName)).toList
      .map(toUpdate.toUpdate)
    new RichRawWorld(ReadModelAddKey.of(context)(updates)(context), Nil)
  }
}

class RichRawWorld(
  val context: Context,
  errors: List[Exception]
) extends RawWorld with LazyLogging {
  def offset: Long = OffsetWorldKey.of(context)
  def reduce(events: List[RawEvent]): RawWorld = if(events.isEmpty) this else try {
    val registry = QAdapterRegistryKey.of(context)
    val updatesAdapter = registry.updatesAdapter
    val updates = events.flatMap(ev ⇒ updatesAdapter.decode(ev.data).updates)
    new RichRawWorld(
      Function.chain(
        Seq(
          ReadModelAddKey.of(context)(updates),
          OffsetWorldKey.set(events.last.offset)
        )
      )(context),
      errors
    )
  } catch {
    case e: Exception ⇒
      logger.error("reduce", e) // ??? exception to record
      if(events.size == 1)
        new RichRawWorld(
          OffsetWorldKey.set(Single(events).offset)(context),
          errors = e :: errors
        )
      else {
        val(a,b) = events.splitAt(events.size / 2)
        reduce(a).reduce(b)
      }
  }
  def hasErrors: Boolean = errors.nonEmpty
}

object WorldStats {
  def make(context: Context): String = context.assembled.collect {
    case (worldKey, index: Map[_, _]) ⇒
      val sz = index.values.collect { case s: Seq[_] ⇒ s.size }.sum
      s"$worldKey : ${index.size} : $sz"
  }.mkString("\n")
}

class StatsObserver(inner: RawObserver) extends RawObserver with LazyLogging {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case richRawWorld: RichRawWorld ⇒
      logger.debug(WorldStats.make(richRawWorld.context))
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
      val newObservers = observers.flatMap(_.activate(richRawWorld.context))
      if(newObservers.isEmpty) completing.activate(rawWorld)
      else new RichRawObserver(newObservers, completing)
  }
}
