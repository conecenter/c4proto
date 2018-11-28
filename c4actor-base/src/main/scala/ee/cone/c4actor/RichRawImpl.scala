package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{FailedUpdates, Firstborn, Offset}
import ee.cone.c4actor.Types.{NextOffset, SharedComponentMap}
import ee.cone.c4assemble._
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

class RichRawWorldFactoryImpl(
  toInjects: List[ToInject], toUpdate: ToUpdate, actorName: String, reducer: RichRawWorldReducer
) extends RichRawWorldFactory {
  def create(): RichContext = {
    val injectedList = for{
      toInject ← toInjects
      injected ← toInject.toInject
    } yield Map(injected.pair)
    val eWorld = CreateRichRawWorld(Merge(Nil,injectedList), emptyReadModel, actorName)
    val firstborn = LEvent.update(Firstborn(actorName)).toList.map(toUpdate.toUpdate)
    val (bytes, headers) = toUpdate.toBytes(firstborn)
    val firstRawEvent = SimpleRawEvent(eWorld.offset, ToByteString(bytes), headers)
    reducer.reduce(List(firstRawEvent))(eWorld)
  }
}

class RichRawWorldReducerImpl(actorName: String) extends RichRawWorldReducer {
  def reduce(events: List[RawEvent]): SharedContext with AssembledContext ⇒ RichContext = context ⇒
    if(events.isEmpty) context match {
      case w: RichRawWorldImpl ⇒ w
      case c ⇒ CreateRichRawWorld(context.injected, context.assembled, actorName)
    } else {
      val nAssembled = ReadModelAddKey.of(context)(context)(events)(context.assembled)
      CreateRichRawWorld(context.injected, nAssembled, actorName)
    }
}

object CreateRichRawWorld {
  def apply(injected: SharedComponentMap, assembled: ReadModel, actorName: String): RichRawWorldImpl = {
    val offset = ByPK(classOf[Offset])
      .of(new RichRawWorldImpl(injected, assembled, ""))
      .get(actorName).fold("0" * OffsetHexSize())(_.txId)
    new RichRawWorldImpl(injected, assembled, offset)
  }
}

class RichRawWorldImpl(
  val injected: SharedComponentMap,
  val assembled: ReadModel,
  val offset: NextOffset
) extends RichContext

object WorldStats {
  def make(context: AssembledContext): String = context.assembled.collect {
    case (worldKey, index: Map[_, _]) ⇒
      val sz = index.values.collect { case s: Seq[_] ⇒ s.size }.sum
      s"$worldKey : ${index.size} : $sz"
  }.mkString("\n")
}

class StatsObserver(inner: RawObserver) extends RawObserver with LazyLogging {
  def activate(rawWorld: RichContext): RawObserver = rawWorld match {
    case ctx: AssembledContext ⇒
      logger.debug(WorldStats.make(ctx))
      logger.info("Stats OK")
      inner
  }
}

class RichRawObserver(
  observers: List[Observer],
  completing: RawObserver
) extends RawObserver {
  def activate(rawWorld: RichContext): RawObserver = rawWorld match {
    case richContext: RichContext ⇒
      val newObservers = observers.flatMap(_.activate(richContext))
      if(newObservers.isEmpty) completing.activate(rawWorld)
      else new RichRawObserver(newObservers, completing)
  }
}
