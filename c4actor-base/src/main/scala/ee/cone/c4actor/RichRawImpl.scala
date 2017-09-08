package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Firstborn
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
  contextFactory: ContextFactory, qMessages: QMessages, actorName: String
) extends RawWorldFactory {
  def create(): RawWorld = {
    val context = contextFactory.create()
    val updates = LEvent.update(Firstborn(actorName)).toList
      .map(qMessages.toUpdate)
    new RichRawWorld(ReadModelAddKey.of(context)(updates)(context), Nil)
  }
}

class RichRawWorld(
  val context: Context,
  errors: List[Exception]
) extends RawWorld {
  def offset: Long = OffsetWorldKey.of(context)
  def reduce(data: Array[Byte], offset: Long): RawWorld = try {
    val registry = QAdapterRegistryKey.of(context)
    val updates = registry.updatesAdapter.decode(data).updates
    new RichRawWorld(
      Function.chain(
        Seq(
          ReadModelAddKey.of(context)(updates),
          OffsetWorldKey.set(offset)
        )
      )(context), errors
    )
  } catch {
    case e: Exception ⇒
      e.printStackTrace() // ??? exception to record
      new RichRawWorld(
        OffsetWorldKey.set(offset)(context),
        errors = e :: errors
      )
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

class StatsObserver(inner: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case richRawWorld: RichRawWorld ⇒
      println(WorldStats.make(richRawWorld.context))
      println("Stats OK")
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
