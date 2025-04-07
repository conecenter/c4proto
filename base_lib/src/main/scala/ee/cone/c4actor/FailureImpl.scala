package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_UpdateFrom, S_Firstborn, S_TxReport}
import ee.cone.c4actor.TxHistoryProtocol._
import ee.cone.c4actor.Types.{LEvents, SrcId, TxEvents}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{ToPrimaryKey, by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{HasId, Id, ProtoAdapter, protocol}

import java.time.Instant
import scala.Function.chain
import scala.collection.immutable.TreeMap

case class TxHistoryImpl(sums: TreeMap[String,String], reports: Map[String,S_TxReport]) extends TxHistory

@protocol("ProtoApp") object TxHistoryProtocol {
  case class N_TxSum(@Id(0x001A) txId: String, @Id(0x0010) sum: String)
  @Id(0x001C) case class S_TxHistory(
    @Id(0x0011) srcId: SrcId, // snapshot tx id
    @Id(0x001F) sums: List[N_TxSum], // 1st txId is unknown, rest are failed
  )
  @Id(0x001D) case class S_TxHistoryPurge(@Id(0x0011) srcId: SrcId)
  @Id(0x001E) case class S_TxHistorySplit(@Id(0x0011) srcId: SrcId, @Id(0x001A) txId: String, @Id(0x002E) until: Long)
}

object SortByPK {
  def apply[T<:Product](l: Iterable[T]): Seq[T] = l.toSeq.sortBy(ToPrimaryKey(_))
}

@c4("RichDataCompApp") final class TxHistoryReducerImpl(
  idGenUtil: IdGenUtil, qAdapterRegistry: QAdapterRegistry, currentProcess: CurrentProcess,
  toUpdate: ToUpdate, updateMapUtil: UpdateMapUtil,
) extends TxHistoryReducer with LazyLogging {
  private type Adapter[T] = ProtoAdapter[T] with HasId
  private def getAdapter[T](cl: Class[T]) = qAdapterRegistry.byName(cl.getName).asInstanceOf[Adapter[T]]
  private def isUpd(adapter: Adapter[_], u: N_UpdateFrom) = u.valueTypeId == adapter.id && u.value.size > 0
  private def filterNot[T](l: List[T], f: T=>Boolean) = if(l.exists(f)) l.filterNot(f) else l
  private def impl(history: TxHistory): TxHistoryImpl = history match { case h: TxHistoryImpl => h }
  //
  def getSum(history: TxHistory, txId: String): Option[String] =
    impl(history).sums.rangeTo(txId).lastOption.map{ case (_,s) => s }
  def isFailed(history: TxHistory, txId: String): Option[Boolean] = {
    val sums = impl(history).sums
    if(txId <= sums.firstKey) None else Option(sums.contains(txId))
  }
  def getUnsentReports(history: TxHistory): Seq[S_TxReport] = SortByPK(impl(history).reports.values)
  def report(txId: String, sum: String, reason: String): S_TxReport = S_TxReport(
    srcId = idGenUtil.srcIdRandom(), electorClientId = currentProcess.id, txId = txId, sum = sum, reason = reason
  )
  private def addFailed(h: TxHistoryImpl, txId: String, err: Throwable) = {
    val (lastTxId, lastSum) = h.sums.last
    if(txId <= lastTxId) throw new Exception("inconsistent txId")
    val sum = idGenUtil.srcIdFromStrings(lastSum, txId)
    val reason = err.getMessage match { case m if m.nonEmpty => m case _ => "?" }
    val rep = report(txId = txId, sum = sum, reason = reason)
    h.copy(sums = h.sums.updated(txId, sum), reports = h.reports.updated(rep.srcId, rep))
  }
  private def keepAfter(h: TxHistoryImpl, id: String) =
    getSum(h, id).fold(h)(sum => h.copy(sums = h.sums.rangeFrom(id).updated(id, sum)))
  private def purgeReport(h: TxHistoryImpl, id: String) = h.copy(reports = h.reports - id)
  //
  private def fromOrig(h: S_TxHistory, txId: String) = if(h.srcId == txId){
    val res = TxHistoryImpl(TreeMap.from(h.sums.map(s => s.txId -> s.sum)), Map.empty)
    val (_, lastSum) = res.sums.last
    logger.info(s"TxHistory load: $txId, ${res.sums.size}, $lastSum")
    Option(res)
  } else {
    logger.warn(s"TxHistory load was ignored for $txId")
    None
  }
  private def toOrig(h: TxHistoryImpl, txId: String) =
    S_TxHistory(txId,h.sums.map{ case (txId,sum) => N_TxSum(txId,sum) }.toList)
  def empty: TxHistory = TxHistoryImpl(TreeMap(""->""), Map.empty)
  //
  def reduce(
    history: TxHistory, updates: List[N_UpdateFrom], txIdOpt: Option[String], errOpt: Option[Throwable]
  ): (TxHistory, List[N_UpdateFrom]) = {
    val reportAdapter = getAdapter(classOf[S_TxReport])
    val historyAdapter = getAdapter(classOf[S_TxHistory])
    val purgeAdapter = getAdapter(classOf[S_TxHistoryPurge])
    val upd = (hist: TxHistoryImpl) => updates.foldLeft(hist)((h,u) =>
      if(isUpd(historyAdapter,u)) fromOrig(historyAdapter.decode(u.value), txIdOpt.get).getOrElse(h)
      else if(isUpd(reportAdapter,u)) purgeReport(h, u.srcId)
      else if(isUpd(purgeAdapter,u)) keepAfter(h, u.srcId)
      else h
    )
    val add = (hist: TxHistoryImpl) => errOpt.fold(hist)(err => addFailed(hist, txIdOpt.get, err))
    (chain(Seq(upd,add))(impl(history)), filterNot(updates, isUpd(historyAdapter, _)))
  }
  def toUpdates(history: TxHistory, txId: String): List[N_UpdateFrom] =
    LEvent.update(toOrig(impl(history), txId)).map(toUpdate.toUpdate).map(updateMapUtil.insert).toList
}

@c4("RichDataCompApp") final class PrevTxFailedUtilImpl(
  reducer: RichRawWorldReducer, hReducer: TxHistoryReducer
) extends PrevTxFailedUtil {
  def prepare(local: Context): Context =
    if(hReducer.isFailed(reducer.history(local), ReadAfterWriteOffsetKey.of(local)).contains(false)) local
    else PrevTxFailedKey.modify(_+1L)(local)
  private def max(a: Instant, b: Instant): Instant = if(a.isAfter(b)) a else b
  def handle(local: Context): Context = PrevTxFailedKey.of(local) match {
    case 0L => local case n => SleepUntilKey.modify(max(_, Instant.now.plusSeconds(n)))(local)
  }
}

/// every tx-s:

@c4assemble("ServerCompApp") class TxHistoryAssembleBase(
  txHistoryEveryTxFactory: TxHistoryEveryTxFactory, actorName: ActorName,
) {
  def every(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId, EnabledTxTr)] =
    Seq(WithPK(EnabledTxTr(txHistoryEveryTxFactory.create())))
  //
  type ByAllRep = SrcId
  def map(key: SrcId, report: Each[S_TxReport]): Values[(ByAllRep,S_TxReport)] = Seq(actorName.value->report)
  def join(
    key: SrcId, @by[ByAllRep] reports: Values[S_TxReport], processes: Each[ReadyProcesses]
  ): Values[(SrcId,TxReportingStatus)] = for {
    maxTxId <- reports.map(_.txId).maxOption.toSeq
    reportedClients = reports.collect{ case r if maxTxId == r.txId => r.electorClientId }.toSet
    p <- processes.all
  } yield WithPK(TxReportingStatus(p.id, maxTxId, reportedClients(p.id)))
}

case class TxReportingStatus(electorClientId: SrcId, maxTxId: String, maxTxReported: Boolean)
@c4("ServerCompApp") final case class GetTxReportingStatus(
  getTxReportingStatus: GetByPK[TxReportingStatus], currentProcess: CurrentProcess,
) {
  private val pid = currentProcess.id
  def of(local: AssembledContext): Option[TxReportingStatus] = getTxReportingStatus.ofA(local).get(pid)
}

@c4multi("ServerCompApp") final case class TxHistoryEveryTx(srcId: SrcId = "TxHistoryEveryTx")(
  reducer: RichRawWorldReducer, hReducer: TxHistoryReducerImpl, txAdd: LTxAdd,
  getTxReportingStatus: GetTxReportingStatus, sleep: Sleep,
) extends TxTransform {
  private def okReport(local: Context, history: TxHistory) = for {
    st <- getTxReportingStatus.of(local) if !st.maxTxReported
    txId = st.maxTxId
    isFailed <- hReducer.isFailed(history, txId)
  } yield hReducer.report(txId = txId, sum = hReducer.getSum(history, txId).get, reason = if(isFailed) "?" else "")
  def transform(local: Context): Context = {
    val history = reducer.history(local)
    val failureReports = hReducer.getUnsentReports(history)
    val events = if(failureReports.nonEmpty) LEvent.update(failureReports)
      else LEvent.update(okReport(local, history).toSeq) ++ sleep.forSeconds(1)
    txAdd.add(events)(local)
  }
}

/// main tx-s:

case object TxHistoryPrevMaxTxIdKey extends TransientLens[Option[String]](None)
case class TxHistoryPrevMaxTxIdEvent(value: Option[String]) extends TransientEvent(TxHistoryPrevMaxTxIdKey, value)
@c4("ServerCompApp") final case class TxReportPurgeTx(srcId: SrcId = "TxReportPurgeTx")(
  getReports: GetByPK[S_TxReport], getTxReportingStatus: GetTxReportingStatus, sleep: Sleep,
) extends SingleTxTr {
  private val period = 60 //s
  def transform(local: Context): TxEvents = {
    val deletes = LEvent.delete(for {
      prevMaxTxId <- TxHistoryPrevMaxTxIdKey.of(local).toSeq
      report <- SortByPK(getReports.ofA(local).values.filter(_.txId < prevMaxTxId))
    } yield report)
    val maxTxIdOpt = getTxReportingStatus.of(local).map(_.maxTxId)
    deletes ++ Seq(TxHistoryPrevMaxTxIdEvent(maxTxIdOpt)) ++ sleep.forSeconds(period)
  }
}

@c4("ServerCompApp") final case class TxHistoryPurgeTx(srcId: SrcId = "TxHistoryPurgeTx")(
  snapshotLister: SnapshotLister, getPurge: GetByPK[S_TxHistoryPurge], sleep: Sleep,
) extends SingleTxTr {
  private val checkPeriod = 60*5 //s
  private val keepPeriod = 60*60*24*30 //s
  def transform(local: Context): TxEvents = {
    val snapshots = snapshotLister.listWithMTime
    val msUntil = snapshots.map(_.mTime).maxOption.fold(0L)(_-keepPeriod*1000)
    val was = getPurge.ofA(local).values.toSet
    val will = snapshots.filter(_.mTime < msUntil).map(_.snapshot.offset).maxOption.map(S_TxHistoryPurge).toSet
    LEvent.delete(SortByPK(was--will)) ++ LEvent.update(SortByPK(will--was)) ++ sleep.forSeconds(checkPeriod)
  }
}

@c4("ServerCompApp") final case class TxHistorySplitTx(srcId: SrcId = "TxHistorySplitTx")(
  getReports: GetByPK[S_TxReport], readyProcessUtil: ReadyProcessUtil, getTxHistorySplit: GetByPK[S_TxHistorySplit],
  sleep: Sleep,
) extends SingleTxTr with LazyLogging {
  private val preventRestartPeriod = 60 * 60 * 2 //s
  private def detected(local: Context) = { // only when single replica is active, there will be not detected, so we prevent restarting all but one;
    val pids = readyProcessUtil.getAll(local).enabledForCurrentRole.toSet
    val reports = getReports.ofA(local).values
    val res = reports.filter(r=>pids(r.electorClientId)).groupBy(_.txId).values.exists(r => r.map(_.sum).toSet.size > 1)
    if(res) logger.error(s"detected tx history split: ${reports.toSeq.sortBy(_.srcId)}")
    res
  }
  private def restartedRecently(local: Context, proc: ReadyProcess, now: Long) = {
    val res = getTxHistorySplit.ofA(local).exists{ case (_,s) => s.txId < proc.txId && now < s.until }
    if(res) logger.error(s"restarted recently")
    res
  }
  private def createHistorySplit(now: Long): LEvents =
    LEvent.update(S_TxHistorySplit("TxHistorySplit", "", now + preventRestartPeriod * 1000))
  def transform(local: Context): TxEvents = {
    val now = System.currentTimeMillis()
    val proc = readyProcessUtil.getCurrent(local)
    if(!detected(local) || proc.completionReqAt.nonEmpty || restartedRecently(local, proc, now)) sleep.forSeconds(1)
    else createHistorySplit(now) ++ proc.complete(Instant.now)
  }
}
