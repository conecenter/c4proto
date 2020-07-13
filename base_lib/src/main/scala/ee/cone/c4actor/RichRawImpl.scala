package ee.cone.c4actor

import java.util.concurrent.ExecutorService

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.ToByteString

import scala.collection.immutable.Map
import scala.concurrent.ExecutionContext
import java.lang.Math.toIntExact

import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4di.{c4, provide}

@c4("RichDataCompApp") final class GetOffsetImpl(
  actorName: ActorName,
  getS_Offset: GetByPK[S_Offset],
) extends GetOffset {
  def of: SharedContext with AssembledContext => NextOffset =
    ctx => getS_Offset.ofA(ctx).get(actorName.value).fold(empty)(_.txId)
  def empty: NextOffset = "0" * OffsetHexSize()
}

object EmptyInjected extends Injected

@c4("RichDataCompApp") final class EmptyInjectedProvider {
  @provide def injected: Seq[Injected] = Nil
}

@c4("RichDataCompApp") final class RichRawWorldReducerImpl(
  injected: List[Injected],
  toUpdate: ToUpdate,
  actorName: ActorName,
  execution: Execution,
  getOffset: GetOffsetImpl,
  readModelAdd: ReadModelAdd,
  getAssembleOptions: GetAssembleOptions,
) extends RichRawWorldReducer with LazyLogging {
  def reduce(contextOpt: Option[SharedContext with AssembledContext], addEvents: List[RawEvent]): RichContext = {
    val events = if(contextOpt.nonEmpty) addEvents else {
      val offset = addEvents.lastOption.fold(getOffset.empty)(_.srcId)
      val firstborn = LEvent.update(S_Firstborn(actorName.value,offset)).toList.map(toUpdate.toUpdate)
      val (bytes, headers) = toUpdate.toBytes(firstborn)
      SimpleRawEvent(offset, ToByteString(bytes), headers) :: addEvents
    }
    if(events.isEmpty) contextOpt.get match {
      case context: RichRawWorldImpl => context
      case context => create(context.injected, context.assembled, context.executionContext)
    } else {
      val context = contextOpt.getOrElse(
        create(Single.option(injected).getOrElse(EmptyInjected), emptyReadModel, EmptyOuterExecutionContext)
      )
      val nAssembled = readModelAdd.add(events, context)
      create(context.injected, nAssembled, context.executionContext)
    }
  }

  def create(injected: Injected, assembled: ReadModel, executionContext: OuterExecutionContext): RichRawWorldImpl = {
    val preWorld = new RichRawWorldImpl(injected, assembled, executionContext, "")
    val threadCount = getAssembleOptions.get(assembled).threadCount
    val offset = getOffset.of(preWorld)
    new RichRawWorldImpl(injected, assembled, needExecutionContext(threadCount)(executionContext), offset)
  }
  def newExecutionContext(confThreadCount: Long): OuterExecutionContext = {
    val fixedThreadCount = if(confThreadCount>0) toIntExact(confThreadCount) else Runtime.getRuntime.availableProcessors
    val pool = execution.newExecutorService("ass-",Option(fixedThreadCount))
    logger.info(s"ForkJoinPool create $fixedThreadCount")
    new OuterExecutionContextImpl(confThreadCount,fixedThreadCount,ExecutionContext.fromExecutor(pool),pool)
  }
  def needExecutionContext(confThreadCount: Long): OuterExecutionContext=>OuterExecutionContext = {
    case ec: OuterExecutionContextImpl if ec.confThreadCount == confThreadCount =>
      ec
    case ec: OuterExecutionContextImpl =>
      ec.service.shutdown()
      logger.info("ForkJoinPool shutdown")
      newExecutionContext(confThreadCount)
    case _ =>
      newExecutionContext(confThreadCount)
  }
}

class OuterExecutionContextImpl(
  val confThreadCount: Long,
  val threadCount: Long,
  val value: ExecutionContext,
  val service: ExecutorService
) extends OuterExecutionContext
object EmptyOuterExecutionContext extends OuterExecutionContext {
  def value: ExecutionContext = throw new Exception("no ExecutionContext")
  def threadCount: Long =  throw new Exception("no ExecutionContext")
}

class RichRawWorldImpl(
  val injected: Injected,
  val assembled: ReadModel,
  val executionContext: OuterExecutionContext,
  val offset: NextOffset
) extends RichContext
/*
object WorldStats {
  def make(context: AssembledContext): String = ""
    Await.result(Future.sequence(
      for {
        (worldKey,indexF) <- context.assembled.inner.toSeq.sortBy(_._1)
      } yield for {
        index <- indexF
      } yield {
        val sz = index.data.values.collect { case s: Seq[_] => s.size }.sum
        s"$worldKey : ${index.size} : $sz"
      }
    ), Duration.Inf).mkString("\n")
}
*/

