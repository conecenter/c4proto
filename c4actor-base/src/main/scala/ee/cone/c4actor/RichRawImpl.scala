package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.{Firstborn, Update}
import ee.cone.c4assemble.Single

import scala.collection.immutable.{Map, Seq}

class ContextFactory(toInject: List[ToInject]) {
  def create(): Context = new Context(
    toInject.flatMap(_.toInject).map(_.pair).groupBy(_._1).transform((k,kvs)⇒Single(kvs)._2),
    Map.empty,
    Map.empty
  )
}

class RichRawWorldFactory(
  contextFactory: ContextFactory, qMessages: QMessages, actorName: String
) extends RawWorldFactory {
  def create(): RawWorld = {
    val context = contextFactory.create()
    val updates = LEvent.update(Firstborn(actorName)).toList.map(qMessages.toUpdate)
    new RichRawWorld(ReadModelAddKey.of(context)(updates)(context),Nil)
  }
}

class RichRawWorld(val context: Context, errors: List[Exception]) extends RawWorld {
  def offset: Long = OffsetWorldKey.of(context)
  def reduce(data: Array[Byte], offset: Long): RawWorld = try {
    val registry = QAdapterRegistryKey.of(context)
    val updates = registry.updatesAdapter.decode(data).updates
    new RichRawWorld(Function.chain(Seq(
      ReadModelAddKey.of(context)(updates),
      OffsetWorldKey.set(offset)
    ))(context), errors)
  } catch {
    case e: Exception ⇒
      e.printStackTrace() // ??? exception to record
      new RichRawWorld(OffsetWorldKey.set(offset)(context), errors = e :: errors)
  }
  def hasErrors: Boolean = errors.nonEmpty
}

object WorldStats {
  def make(context: Context): String = context.assembled.collect{
    case (worldKey, index:Map[_,_]) ⇒
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

class RichRawObserver(observers: List[Observer], completing: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = rawWorld match {
    case richRawWorld: RichRawWorld ⇒
      val newObservers = observers.flatMap(_.activate(richRawWorld.context))
      if(newObservers.isEmpty) completing.activate(rawWorld)
      else new RichRawObserver(newObservers,completing)
  }
}
