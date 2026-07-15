package ee.cone.c4actor

import java.util.concurrent.{Executor, ExecutorService}
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

@c4("RichDataCompApp") final class GetOffsetImpl(
  actorName: ActorName,
  getS_Offset: GetByPK[S_Offset],
) extends GetOffset {
  def of: SharedContext with AssembledContext => NextOffset =
    ctx => getS_Offset.ofA(ctx).get(actorName.value).fold(empty)(_.txId)
  def empty: NextOffset = "0" * OffsetHexSize()
}

object EmptyInjected extends Injected

@c4("RichDataCompApp") final class RichRawWorldReducerImpl(
  injected: List[Injected],
  toUpdate: ToUpdate,
  actorName: ActorName,
  getOffset: GetOffsetImpl,
  readModelAdd: ReadModelAdd,
  updateMapUtil: UpdateMapUtil,
  replaces: DeferredSeq[Replace],
) extends RichRawWorldReducer with LazyLogging {
  def reduce(contextOpt: Option[SharedContext with AssembledContext], addEvents: List[RawEvent]): RichContext = {
    val events = if(contextOpt.nonEmpty) addEvents else {
      val offset = addEvents.lastOption.fold(getOffset.empty)(_.srcId)
      val fUpdates = LEvent.update(S_Firstborn(actorName.value,offset))
        .map(toUpdate.toUpdate).map(updateMapUtil.insert).toList
      val (bytes, headers) = toUpdate.toBytes(fUpdates)
      SimpleRawEvent(offset, ToByteString(bytes), headers) :: addEvents
    }
    if(events.isEmpty) contextOpt.get match {
      case context: RichRawWorldImpl => context
      case context => create(context.injected, context.assembled)
    } else {
      val context = contextOpt.getOrElse(
        create(Single.option(injected).getOrElse(EmptyInjected), Single(replaces.value).emptyReadModel)
      )
      create(context.injected, readModelAdd.add(events)(context.assembled))
    }
  }
  def create(injected: Injected, assembled: ReadModel): RichRawWorldImpl = {
    val preWorld = new RichRawWorldImpl(injected, assembled, EmptyOuterExecutionContext, "")
    val offset = getOffset.of(preWorld)
    new RichRawWorldImpl(injected, assembled, EmptyOuterExecutionContext, offset)
  }
}

object EmptyOuterExecutionContext extends OuterExecutionContext

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

