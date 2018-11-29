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

class RichRawWorldReducerImpl(
  toInjects: List[ToInject], toUpdate: ToUpdate, actorName: String
) extends RichRawWorldReducer {
  def reduce(contextOpt: Option[SharedContext with AssembledContext], addEvents: List[RawEvent]): RichContext = {
    val events = if(contextOpt.nonEmpty) addEvents else {
      val offset = addEvents.lastOption.fold(emptyOffset)(_.srcId)
      val firstborn = LEvent.update(Firstborn(actorName,offset)).toList.map(toUpdate.toUpdate)
      val (bytes, headers) = toUpdate.toBytes(firstborn)
      SimpleRawEvent(offset, ToByteString(bytes), headers) :: addEvents
    }
    if(events.isEmpty) contextOpt.get match {
      case context: RichRawWorldImpl ⇒ context
      case context ⇒ create(context.injected, context.assembled)
    } else {
      val context = contextOpt.getOrElse{
        val injectedList = for{
          toInject ← toInjects
          injected ← toInject.toInject
        } yield Map(injected.pair)
        create(Merge(Nil,injectedList), emptyReadModel)
      }
      val nAssembled = ReadModelAddKey.of(context)(context)(events)(context.assembled)
      create(context.injected, nAssembled)
    }
  }
  def emptyOffset: NextOffset = "0" * OffsetHexSize()
  def create(injected: SharedComponentMap, assembled: ReadModel): RichRawWorldImpl = {
    val offset = ByPK(classOf[Offset])
      .of(new RichRawWorldImpl(injected, assembled, ""))
      .get(actorName).fold(emptyOffset)(_.txId)
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
