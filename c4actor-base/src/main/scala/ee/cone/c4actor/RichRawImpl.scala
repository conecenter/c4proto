package ee.cone.c4actor

import java.util.concurrent.ExecutorService

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{S_Firstborn, S_Offset}
import ee.cone.c4actor.Types.{NextOffset, SharedComponentMap}
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.ToByteString

import scala.collection.immutable.Map
import scala.concurrent.ExecutionContext
import java.lang.Math.toIntExact

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
  toInjects: List[ToInject], toUpdate: ToUpdate, actorName: String, execution: Execution
) extends RichRawWorldReducer with LazyLogging {
  def reduce(contextOpt: Option[SharedContext with AssembledContext], addEvents: List[RawEvent]): RichContext = {
    val events = if(contextOpt.nonEmpty) addEvents else {
      val offset = addEvents.lastOption.fold(emptyOffset)(_.srcId)
      val firstborn = LEvent.update(S_Firstborn(actorName,offset)).toList.map(toUpdate.toUpdate)
      val (bytes, headers) = toUpdate.toBytes(firstborn)
      SimpleRawEvent(offset, ToByteString(bytes), headers) :: addEvents
    }
    if(events.isEmpty) contextOpt.get match {
      case context: RichRawWorldImpl ⇒ context
      case context ⇒ create(context.injected, context.assembled, context.executionContext)
    } else {
      val context = contextOpt.getOrElse{
        val injectedList = for{
          toInject ← toInjects
          injected ← toInject.toInject
        } yield Map(injected.pair)
        create(Merge(Nil,injectedList), emptyReadModel, EmptyOuterExecutionContext)
      }
      val nAssembled = ReadModelAddKey.of(context)(events)(context)
      create(context.injected, nAssembled, context.executionContext)
    }
  }
  def emptyOffset: NextOffset = "0" * OffsetHexSize()
  def create(injected: SharedComponentMap, assembled: ReadModel, executionContext: OuterExecutionContext): RichRawWorldImpl = {
    val preWorld = new RichRawWorldImpl(injected, assembled, executionContext, "")
    val threadCount = GetAssembleOptions.of(preWorld)(assembled).threadCount
    val offset = ByPK(classOf[S_Offset]).of(preWorld).get(actorName).fold(emptyOffset)(_.txId)
    new RichRawWorldImpl(injected, assembled, needExecutionContext(threadCount)(executionContext), offset)
  }
  def newExecutionContext(threadCount: Long): OuterExecutionContext = {
    val fixedThreadCount = if(threadCount>0) toIntExact(threadCount) else Runtime.getRuntime.availableProcessors
    val pool = execution.newExecutorService("ass-",Option(fixedThreadCount))
    logger.info(s"ForkJoinPool create $fixedThreadCount")
    new OuterExecutionContextImpl(threadCount,ExecutionContext.fromExecutor(pool),pool)
  }
  def needExecutionContext(threadCount: Long): OuterExecutionContext⇒OuterExecutionContext = {
    case ec: OuterExecutionContextImpl if ec.threadCount == threadCount ⇒
      ec
    case ec: OuterExecutionContextImpl ⇒
      ec.service.shutdown()
      logger.info("ForkJoinPool shutdown")
      newExecutionContext(threadCount)
    case _ ⇒
      newExecutionContext(threadCount)
  }
}

class OuterExecutionContextImpl(
  val threadCount: Long,
  val value: ExecutionContext,
  val service: ExecutorService
) extends OuterExecutionContext
object EmptyOuterExecutionContext extends OuterExecutionContext {
  def value: ExecutionContext = throw new Exception("no ExecutionContext")
}

class RichRawWorldImpl(
  val injected: SharedComponentMap,
  val assembled: ReadModel,
  val executionContext: OuterExecutionContext,
  val offset: NextOffset
) extends RichContext

object WorldStats {
  def make(context: AssembledContext): String = ""
    /*Await.result(Future.sequence(
      for {
        (worldKey,indexF) ← context.assembled.inner.toSeq.sortBy(_._1)
      } yield for {
        index ← indexF
      } yield {
        val sz = index.data.values.collect { case s: Seq[_] ⇒ s.size }.sum
        s"$worldKey : ${index.size} : $sz"
      }
    ), Duration.Inf).mkString("\n")*/
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
  observers: List[Observer[RichContext]],
  completing: RawObserver
) extends RawObserver {
  def activate(rawWorld: RichContext): RawObserver = rawWorld match {
    case richContext: RichContext ⇒
      val newObservers = observers.flatMap(_.activate(richContext))
      if(newObservers.isEmpty) completing.activate(rawWorld)
      else new RichRawObserver(newObservers, completing)
  }
}
