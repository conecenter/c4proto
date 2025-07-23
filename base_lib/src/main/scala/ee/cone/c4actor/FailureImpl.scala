package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_UpdateFrom, S_Firstborn, S_TxReport}
import ee.cone.c4actor.TxHistoryProtocol._
import ee.cone.c4actor.Types.{LEvents, NextOffset, SrcId, TxEvents}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, ToPrimaryKey, by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{HasId, Id, ProtoAdapter, protocol}

import java.time.Instant
import scala.Function.chain
import scala.collection.immutable.TreeMap

case class TxHistoryImpl(
  sums: TreeMap[String,String],
  // error reports that was not yet shared between replicas;
  // reducer adds one when reduce fails;
  // then txtr in replica sends it as update;
  // then reducer removes it from TxHistory, when it comes back to reduce as update to the world;
  reports: Map[String,S_TxReport]
) extends TxHistory

@protocol("ProtoApp") object TxHistoryProtocol {
  case class N_TxSum(@Id(0x0019) txId: String, @Id(0x0010) sum: String)
  @Id(0x001C) case class S_TxHistory(
    @Id(0x0011) srcId: SrcId, // snapshot tx id
    @Id(0x001F) sums: List[N_TxSum], // 1st txId is unknown, rest are failed
  )
  @Id(0x001D) case class S_TxHistoryPurge(
    // points to tx after which reducer will keep history;
    // replaced by more recent periodically according to creation times of snapshots
    @Id(0x0011) srcId: SrcId
  )
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
  def addFailed(history: TxHistory, txId: String, err: Throwable): TxHistory = {
    val h = impl(history)
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
  def reduce(history: TxHistory, updates: List[N_UpdateFrom], txIdOpt: Option[NextOffset]): (TxHistory, List[N_UpdateFrom]) = {
    val reportAdapter = getAdapter(classOf[S_TxReport])
    val historyAdapter = getAdapter(classOf[S_TxHistory])
    val purgeAdapter = getAdapter(classOf[S_TxHistoryPurge])
    val hist = updates.foldLeft(impl(history))((h,u) =>
      if(isUpd(historyAdapter,u)) fromOrig(historyAdapter.decode(u.value), txIdOpt.get).getOrElse(h)
      else if(isUpd(reportAdapter,u)) purgeReport(h, u.srcId)
      else if(isUpd(purgeAdapter,u)) keepAfter(h, u.srcId)
      else h
    )
    (hist, filterNot(updates, isUpd(historyAdapter, _)))
  }

  def toUpdates(history: TxHistory, txId: String): List[N_UpdateFrom] =
    LEvent.update(toOrig(impl(history), txId)).map(toUpdate.toUpdate).map(updateMapUtil.insert).toList
}

/// every tx-s:
// unsent failure-reports are written instantly;
// reports are aggregated to TxReportingStatus-s of replica-s, where maxTxId is common for all replicas;
// ok-report is written in response to failure-report by other replica (if not maxTxReported);
// need to write ok-reports are checked every second;

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

// reports are purged every `period`;
// we remember their max-tx-id from period ago, then purge `< prevMaxTxId` (keeping for last tx);
case object TxHistoryPrevMaxTxIdKey extends TransientLens[Option[String]](None)
case class TxHistoryPrevMaxTxIdEvent(value: Option[String]) extends TransientEvent(TxHistoryPrevMaxTxIdKey, value)
@c4("ServerCompApp") final case class TxReportPurgeTx(srcId: SrcId = "TxReportPurgeTx")(
  getReports: GetByPK[S_TxReport], getTxReportingStatus: GetTxReportingStatus, sleep: Sleep,
) extends SingleTxTr with LazyLogging {
  private val period = 60 //s
  def transform(local: Context): TxEvents = {
    val reports = SortByPK(getReports.ofA(local).values)
    logger.debug(s"$reports")
    val deletes = LEvent.delete(for {
      prevMaxTxId <- TxHistoryPrevMaxTxIdKey.of(local).toSeq
      report <- reports.filter(_.txId < prevMaxTxId)
    } yield report)
    val maxTxIdOpt = getTxReportingStatus.of(local).map(_.maxTxId)
    deletes ++ Seq(TxHistoryPrevMaxTxIdEvent(maxTxIdOpt)) ++ sleep.forSeconds(period)
  }
}

@c4("ServerCompApp") final case class TxHistoryPurgeTx(srcId: SrcId = "TxHistoryPurgeTx")(
  snapshotLister: SnapshotLister, getPurge: GetByPK[S_TxHistoryPurge], sleep: Sleep, reducer: RichRawWorldReducer,
) extends SingleTxTr with LazyLogging {
  private val checkPeriod = 60*5 //s
  private val keepPeriod = 60*60*24*30 //s
  def transform(local: Context): TxEvents = {
    val snapshots = snapshotLister.listWithMTime
    val msUntil = snapshots.map(_.mTime).maxOption.fold(0L)(_-keepPeriod*1000)
    val was = getPurge.ofA(local).values.toSet
    val will = snapshots.filter(_.mTime < msUntil).map(_.snapshot.offset).maxOption.map(S_TxHistoryPurge).toSet
    logger.debug(s"$will ... ${reducer.history(local)}")
    LEvent.delete(SortByPK(was--will)) ++ LEvent.update(SortByPK(will--was)) ++ sleep.forSeconds(checkPeriod)
  }
}

@c4("ServerCompApp") final case class TxHistorySplitTx(srcId: SrcId = "TxHistorySplitTx")(
  getReports: GetByPK[S_TxReport], readyProcessUtil: ReadyProcessUtil, getTxHistorySplit: GetByPK[S_TxHistorySplit],
  sleep: Sleep, config: ListConfig,
) extends SingleTxTr with LazyLogging {
  private val preventRestartPeriod =
    Single.option(config.get("C4PREVENT_RESTART_PERIOD_SEC")).fold(60L * 60L * 2L)(_.toLong)
  private def detected(local: Context) = { // only when single replica is active, there will be not detected, so we prevent restarting all but one;
    val pids = readyProcessUtil.getAll(local).enabledForCurrentRole.toSet
    val reportsOfActiveReplicas = getReports.ofA(local).values.filter(r=>pids(r.electorClientId)).toSeq
    val res = reportsOfActiveReplicas.groupBy(_.txId).collect{ case (txk,r) if r.map(_.sum).toSet.size > 1 => txk }.toSeq.sorted
    if(res.nonEmpty){
      logger.error(s"detected tx history split, txs: $res")
      for(rep <- reportsOfActiveReplicas.sortBy(r => (r.txId,r.srcId))) logger.error(s"a rep: $rep")
    }
    res.nonEmpty
  }
  private def restartedRecently(splitOpt: Option[S_TxHistorySplit], proc: ReadyProcess) = {
    val res = splitOpt.exists(split => split.txId < proc.txId)
    logger.info(if(res) "restarted recently" else "not restarted yet")
    res
  }
  private def needHistorySplit(splitOpt: Option[S_TxHistorySplit], now: Long): LEvents = {
    val res =
      if(splitOpt.nonEmpty) Nil
      else LEvent.update(S_TxHistorySplit("TxHistorySplit", "", now + preventRestartPeriod * 1000))
    logger.info(if(res.isEmpty) "using existing split" else "creating new split")
    res
  }
  def transform(local: Context): TxEvents = {
    val now = System.currentTimeMillis()
    val proc = readyProcessUtil.getCurrent(local)
    val splitOpt = Single.option(getTxHistorySplit.ofA(local).values.filter(s => now < s.until).toSeq)
    if(proc.completionReqAt.nonEmpty || !detected(local) || restartedRecently(splitOpt, proc)) sleep.forSeconds(1)
    else needHistorySplit(splitOpt, now) ++ proc.complete(Instant.now)
  }
}

/*
testing:
  +make some assemble-error; observe report-updates by different replicas;
  +then make flaky error; observe ok-report from some replica;
  +observe split detected; make a few times: observe case with 1 and 2 restarts, no more;
  +observe non-last reports are deleted after a period, and last reports are kept;
+history persists in snapshot: have some failure history, make snap, restart;
-check weeks later that S_TxHistoryPurge contains some reasonable offset, and there are no history points earlier;

Even if we write duplicate fail-report, it might not be problem, if sum is same;
*/
