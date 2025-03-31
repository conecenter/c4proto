package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble._

import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4di.c4

@c4("RichDataCompApp") final class GetOffsetImpl extends GetOffset {
  def of: AssembledContext => NextOffset = {
    case local: Context => ParentContextKey.of(local).get.offset case global: RichRawWorldImpl => global.offset
  }
  def empty: NextOffset = "0" * OffsetHexSize()
}

@c4("RichDataCompApp") final class RichRawWorldReducerImpl(
  toUpdate: ToUpdate, actorName: ActorName, getOffset: GetOffsetImpl,
  readModelAdd: ReadModelAdd, updateMapUtil: UpdateMapUtil, replaces: DeferredSeq[Replace], catchNonFatal: CatchNonFatal,
  snapshotConfig: SnapshotConfig, handlers: List[WorldCheckHandler], txHistoryUtil: TxHistoryReducer,
  ignoreRegistry: SnapshotPatchIgnoreRegistry, config: ListConfig,
) extends RichRawWorldReducer with LazyLogging {
  val appCanRevert: Boolean = config.get("C4CAN_REVERT").exists(_.nonEmpty)
  private def toUp(item: Product) = LEvent.update(item).map(toUpdate.toUpdate).toList
  private def impl(context: RichContext) = context match { case c: RichRawWorldImpl => c }
  def createContext(events: Option[RawEvent]): RichContext = {
    val assembled = Single(replaces.value).emptyReadModel
    val snapshot = updateMapUtil.startSnapshot(snapshotConfig.ignore)
    val reverting = updateMapUtil.startSnapshot(ignoreRegistry.ignore)
    val context = new RichRawWorldImpl(assembled, snapshot, reverting, getOffset.empty, txHistoryUtil.empty)
    val firstborn = toUp(S_Firstborn(actorName.value, events.fold(getOffset.empty)(_.srcId)))
    add(context, firstborn, events.toList, None, canRevert = false)
  }
  def reduce(events: Seq[RawEvent]): RichContext=>RichContext =
    context => if(events.isEmpty) context else catchNonFatal[RichContext] {
      add(impl(context), Nil, events.toList, None, canRevert = appCanRevert)
    }("reduce"){ e => // ??? exception to record
      if(events.size == 1) add(impl(context), Nil, events.toList, Option(e), canRevert = appCanRevert) else {
        val(a,b) = events.splitAt(events.size / 2)
        Function.chain(Seq(reduce(a), reduce(b)))(context)
      }
    }
  def add(
    context: RichRawWorldImpl, firstborn: List[N_Update], events: List[RawEvent], errOpt: Option[Throwable],
    canRevert: Boolean
  ): RichRawWorldImpl = {
    val eventIds = events.map(_.srcId)
    (new AssemblerProfiling).debugOffsets(s"starts-reducing ${errOpt.isEmpty}", eventIds)
    val updates = if(errOpt.nonEmpty) Nil else events.flatMap(toUpdate.toUpdates(_,"rma"))
    val onlyTxId = eventIds match { case Seq(id) => Option(id) case _ => None }
    val (history, snUpdates) = txHistoryUtil.reduce(context.history, updates, onlyTxId, errOpt)
    val snapshot = context.snapshot.add(snUpdates, _=>true)
    val reverting = if(canRevert) context.reverting.add(snUpdates, _=>true) else context.reverting
    val nOffset = eventIds.maxOption.getOrElse(context.offset)
    val updatesL = firstborn ::: snUpdates.map(toUpdate.toUpdateLost)
    val nAssembled = readModelAdd.add(eventIds, updatesL)(context.assembled)
    val willContext = new RichRawWorldImpl(nAssembled, snapshot, reverting, nOffset, history)
    if(handlers.nonEmpty){
      logger.info(s"reduced tx $eventIds")
      handlers.foreach(_.handle(willContext))
    }
    willContext
  }
  def toLocal(context: RichContext, transient: TransientMap): Context =
    new Context(context.assembled, transient.updated(ParentContextKey, Option(context)))
  def toHistoryUpdates(local: Context): List[N_UpdateFrom] = {
    val context = impl(ParentContextKey.of(local).get)
    txHistoryUtil.toUpdates(context.history, context.offset)
  }
  def toSnapshotUpdates(local: Context): List[N_UpdateFrom] = impl(ParentContextKey.of(local).get).snapshot.result
  def toRevertUpdates(local: Context): List[N_UpdateFrom] = impl(ParentContextKey.of(local).get).reverting.result
  def history(local: Context): TxHistory = impl(ParentContextKey.of(local).get).history
}
case object ParentContextKey extends TransientLens[Option[RichContext]](None)
class RichRawWorldImpl(
  val assembled: ReadModel, val snapshot: UpdateMapping, val reverting: UpdateMapping,
  val offset: NextOffset, val history: TxHistory,
) extends RichContext
