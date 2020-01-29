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
import ee.cone.c4di.c4

object Merge {
  def apply[A](path: List[Any], values: List[A]): A =
    if(values.size <= 1) Single(values)
    else {
      val maps = values.collect{ case m: Map[_,_] => m.toList }
      assert(values.size == maps.size, s"can not merge $values of $path")
      maps.flatten
        .groupBy(_._1).transform((k,kvs)=>Merge(k :: path, kvs.map(_._2)))
            .asInstanceOf[A]
    }
}

@c4("RichDataCompApp") class GetOffsetImpl(
  actorName: ActorName,
  getS_Offset: GetByPK[S_Offset],
) extends GetOffset {
  def of: SharedContext with AssembledContext => NextOffset =
    ctx => getS_Offset.ofA(ctx).get(actorName.value).fold(empty)(_.txId)
  def empty: NextOffset = "0" * OffsetHexSize()
}

@c4("RichDataCompApp") class RichRawWorldReducerImpl(
  toInjects: List[ToInject], toUpdate: ToUpdate, actorName: ActorName, execution: Execution, getOffset: GetOffsetImpl
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
      val context = contextOpt.getOrElse{
        val injectedList = for{
          toInject <- toInjects
          injected <- toInject.toInject
        } yield Map(injected.pair)
        create(Merge(Nil,injectedList), emptyReadModel, EmptyOuterExecutionContext)
      }
      val nAssembled = ReadModelAddKey.of(context)(events)(context)
      create(context.injected, nAssembled, context.executionContext)
    }
  }

  def create(injected: SharedComponentMap, assembled: ReadModel, executionContext: OuterExecutionContext): RichRawWorldImpl = {
    val preWorld = new RichRawWorldImpl(injected, assembled, executionContext, "")
    val threadCount = GetAssembleOptions.of(preWorld)(assembled).threadCount
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
  val injected: SharedComponentMap,
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

