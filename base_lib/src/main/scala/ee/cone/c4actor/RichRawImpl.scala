package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble._
import scala.concurrent.ExecutionContext
import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4di.c4

@c4("RichDataCompApp") final class GetOffsetImpl(
  actorName: ActorName, getS_Offset: GetByPK[S_Offset],
) extends GetOffset {
  def of: AssembledContext => NextOffset = ctx => getS_Offset.ofA(ctx).get(actorName.value).fold(empty)(_.txId)
  def empty: NextOffset = "0" * OffsetHexSize()
}

@c4("RichDataCompApp") final class RichRawWorldReducerImpl(
  toUpdate: ToUpdate, actorName: ActorName, execution: Execution, getOffset: GetOffsetImpl,
  readModelAdd: ReadModelAdd, updateMapUtil: UpdateMapUtil, replaces: DeferredSeq[Replace], catchNonFatal: CatchNonFatal,
  snapshotConfig: SnapshotConfig, handlers: List[WorldCheckHandler], commits: Commits,
) extends RichRawWorldReducer with LazyLogging {
  private def toUp(item: Product) = LEvent.update(item).map(toUpdate.toUpdate).toList
  private def newExecutionContext(): OuterExecutionContext = {
    val fixedThreadCount = Runtime.getRuntime.availableProcessors
    val pool = execution.newExecutorService("ass-",Option(fixedThreadCount))
    logger.info(s"ForkJoinPool create $fixedThreadCount")
    val context = ExecutionContext.fromExecutor(pool)
    new OuterExecutionContextImpl(context)
  }
  def createContext(events: Option[RawEvent]): RichContext = {
    val executionContext = newExecutionContext()
    val assembled = Single(replaces.value).emptyReadModel
    val snapshot = updateMapUtil.startSnapshot(snapshotConfig.ignore)
    val context = new RichRawWorldImpl(assembled, executionContext, snapshot, getOffset.empty)
    val firstborn = toUp(S_Firstborn(actorName.value, events.fold(getOffset.empty)(_.srcId)))
    add(firstborn, events.toList)(context)
  }
  def reduce(events: Seq[RawEvent]): RichContext=>RichContext = context => if(events.isEmpty) context else catchNonFatal {
    add(Nil, events.toList)(context)
  }("reduce"){ e => // ??? exception to record
    if(events.size == 1){
      add(events.flatMap(ev=>toUp(S_FailedUpdates(ev.srcId, e.getMessage))).toList, Nil)(context)
    } else {
      val(a,b) = events.splitAt(events.size / 2)
      Function.chain(Seq(reduce(a), reduce(b)))(context)
    }
  }
  def add(transientUpdates: List[N_Update], events: List[RawEvent]): RichContext=>RichContext = {
    case context: RichRawWorldImpl =>
      val eventIds = events.map(_.srcId)
      (new AssemblerProfiling).debugOffsets("starts-reducing", eventIds)
      val snUpdates = toUpdate.toUpdates(events,"rma")
      val snapshot = context.snapshot.add(snUpdates, up => commits.check(up))
      val nOffset = eventIds.maxOption.getOrElse(context.offset)
      val offsetUp = toUp(S_Offset(actorName.value,nOffset))
      val updatesL = transientUpdates ::: offsetUp ::: snUpdates.map(toUpdate.toUpdateLost)
      val nAssembled = readModelAdd.add(context.executionContext, eventIds, updatesL)(context.assembled)
      val willContext = new RichRawWorldImpl(nAssembled, context.executionContext, snapshot, nOffset)
      if(handlers.nonEmpty){
        logger.info(s"reduced tx $eventIds")
        handlers.foreach(_.handle(willContext))
      }
      willContext
  }
  def toLocal(context: RichContext, transient: TransientMap): Context =
    new Context(context.assembled, context.executionContext, transient.updated(ParentContextKey, Option(context)))
  def toUpdates(local: Context): List[N_UpdateFrom] =
    ParentContextKey.of(local) match { case Some(c: RichRawWorldImpl) => c.snapshot.result }
}
case object ParentContextKey extends TransientLens[Option[RichContext]](None)
class OuterExecutionContextImpl(val value: ExecutionContext) extends OuterExecutionContext
class RichRawWorldImpl(
  val assembled: ReadModel, val executionContext: OuterExecutionContext, val snapshot: UpdateMapping,
  val offset: NextOffset
) extends RichContext
