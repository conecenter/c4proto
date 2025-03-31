package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_UpdateFrom, S_Firstborn, S_TxReport}
import ee.cone.c4actor.TxHistoryProtocol._
import ee.cone.c4actor.TxHistoryUtilImpl._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{ToPrimaryKey, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{HasId, Id, ProtoAdapter, protocol}

import java.time.Instant
import scala.Function.chain
import scala.collection.immutable.TreeMap

case class TxHistoryImpl(sums: TreeMap[String,String], reports: Map[String,S_TxReport]) extends TxHistory

@protocol("ProtoApp") object TxHistoryProtocol {
  case class N_TxSum(
    txId: String,
    sum: String
  )
  @Id(0x001C) case class S_TxHistory(
    @Id(0x0011) srcId: SrcId, // snapshot tx id
    sums: List[N_TxSum], // 1st txId is unknown, rest are failed
  )
  @Id(0x001D) case class S_TxHistoryPurge(@Id(0x0011) srcId: SrcId)
  case class S_TxHistorySplit(@Id(0x0011) srcId: SrcId, @Id(0x001A) txId: String, @Id(0x002E) until: Long)
}

object TxHistoryUtilImpl {
  def getMaxTxId(reports: Iterable[S_TxReport]): Option[SrcId] = reports.map(_.txId).maxOption
  def sortPK[T<:Product](l: Iterable[T]): Seq[T] = l.toSeq.sortBy(ToPrimaryKey(_))
}

@c4assemble("ServerCompApp") class TxHistoryAssembleBase(txHistoryEveryTxFactory: TxHistoryEveryTxFactory) {
  def every(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId, EnabledTxTr)] =
    Seq(WithPK(EnabledTxTr(txHistoryEveryTxFactory.create())))
}

@c4multi("ServerCompApp") final case class TxHistoryEveryTx(srcId: SrcId = "TxHistoryEveryTx")(
  reducer: RichRawWorldReducer, idGenUtil: IdGenUtil, getReports: GetByPK[S_TxReport], currentProcess: CurrentProcess,
  getOffset: GetOffset, txAdd: LTxAdd, hReducer: TxHistoryReducerImpl,
) extends TxTransform {
  def transform(local: Context): Context = {
    val history = reducer.history(local)
    val failureReports = hReducer.getReports(history)
    val events = if(failureReports.nonEmpty) LEvent.update(failureReports) else {
      val reports = getReports.ofA(local).values
      val pid = currentProcess.id
      val okReports = getMaxTxId(reports).filter(txId =>
        txId > getMaxTxId(reports.filter(_.electorClientId == pid)).getOrElse("") && txId <= getOffset.of(local)
      ).flatMap { txId =>
        hReducer.isFailed(history, txId).map{ isFailed =>
          hReducer.report(txId = txId, sum = hReducer.getSum(history, txId).get, reason = if(isFailed) "?" else "")
        }
      }.toSeq
      LEvent.update(okReports) ++ Seq(SleepUntilEvent(Instant.now.plusSeconds(1)))
    }
    txAdd.add(events)(local)
  }
}

case object TxHistoryPrevMaxTxIdKey extends TransientLens[Option[String]](None)
case class TxHistoryPrevMaxTxIdEvent(value: Option[String]) extends TransientEvent(TxHistoryPrevMaxTxIdKey, value)
@c4("ServerCompApp") final case class TxReportPurgeTx(srcId: SrcId = "TxReportPurgeTx")(
  getReports: GetByPK[S_TxReport], txAdd: LTxAdd,
) extends SingleTxTr {
  private val period = 60 //s
  def transform(local: Context): Context = {
    val reports = sortPK(getReports.ofA(local).values)
    val events = LEvent.delete(TxHistoryPrevMaxTxIdKey.of(local).toSeq.flatMap(m => reports.filter(_.txId < m))) ++
      Seq(TxHistoryPrevMaxTxIdEvent(getMaxTxId(reports)), SleepUntilEvent(Instant.now.plusSeconds(period)))
    txAdd.add(events)(local)
  }
}

@c4("ServerCompApp") final case class TxHistoryPurgeTx(srcId: SrcId = "TxHistoryPurgeTx")(
  txAdd: LTxAdd, snapshotLister: SnapshotLister, getPurge: GetByPK[S_TxHistoryPurge],
) extends SingleTxTr {
  private val checkPeriod = 60*5 //s
  private val keepPeriod = 60*60*24*30 //s
  def transform(local: Context): Context = {
    val snapshots = snapshotLister.listWithMTime
    val msUntil = snapshots.map(_.mTime).maxOption.fold(0L)(_-keepPeriod*1000)
    val was = getPurge.ofA(local).values.toSet
    val will = snapshots.filter(_.mTime < msUntil).map(_.snapshot.offset).maxOption.map(S_TxHistoryPurge).toSet
    val events = LEvent.delete(sortPK(was--will)) ++ LEvent.update(sortPK(will--was)) ++
      Seq(SleepUntilEvent(Instant.now.plusSeconds(checkPeriod)))
    txAdd.add(events)(local)
  }
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
  def getReports(history: TxHistory): Seq[S_TxReport] = sortPK(impl(history).reports.values)
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

///

trait ReadyProcessesUtil {
  def get(local: AssembledContext): ReadyProcesses
}

class ReadyProcessesUtilImpl(
  getReadyProcesses: GetByPK[ReadyProcesses], actorName: ActorName,
) extends ReadyProcessesUtil {
  def get(local: AssembledContext): ReadyProcesses = getReadyProcesses.ofA(local)(actorName.value)
}

@c4("ServerCompApp") final case class TxHistorySplitTx(srcId: SrcId = "TxHistorySplitTx")(
  getReports: GetByPK[S_TxReport], txAdd: LTxAdd, readyProcessesUtil: ReadyProcessesUtil,
  getTxHistorySplit: GetByPK[S_TxHistorySplit],
) extends SingleTxTr {
  def transform(local: Context): Context = {
    val detected = { // only when single replica is active, there will be not detected, so we prevent restarting all but one;
      val pids = readyProcessesUtil.get(local).enabledForCurrentRole.toSet
      getReports.ofA(local).values.filter(r=>pids(r.electorClientId)).groupBy(_.txId).collect{
        case (txId,reports) if reports.map(_.sum).toSet.size > 1 => txId -> reports.toSeq.sortBy(_.srcId)
      }
    }
    val events = if(detected.isEmpty) Seq(SleepUntilEvent(Instant.now.plusSeconds(1))) else {
      val now = System.currentTimeMillis()
      val lastDetectionOpt = getTxHistorySplit.ofA(local).values.maxByOption(_.txId)


      _.until > now

    }





  }
}

/*
detected: same tx, different sum, alive replicas (count)
active-split: now < max-split-until
detected and no active-split => create split ?current-offset to now+2h
detected and active-split and master was born before => restart

 */






// structures: history in memory, history in snapshot, report, purge, split stage


// queue for S_TxReport-s with reason; from reducer to every-replica-TxTr
// every-replica-TxTr sends them to inbox
// every-replica-TxTr sends non-failed report if it sees offset passed, and it has no corresponding failure

// list snapshots and select last that is older than ~month
// can purge ignored before its offset
// can list and purge right in consumer loop, limited by `purgedAt`

// extract reasons



// todo: detect split; upd S_TxHistorySplit




/*
case class FailedTx(txId: SrcId, updates: List[S_FailedUpdates])
case class UnclassifiedFailedTx(tx: FailedTx)

@c4("RichDataCompApp") final class FailureUtilImpl(
  toUpdate: ToUpdate, qAdapterRegistry: QAdapterRegistry, idGenUtil: IdGenUtil,
  currentProcess: CurrentProcess, getFailedTx: GetByPK[FailedTx],
) extends FailureUtil {
  private def isUpdateOf(id: Long): N_UpdateFrom=>Boolean = up => up.valueTypeId == id && up.value.size > 0
  private val isFailedUpdates = isUpdateOf(qAdapterRegistry.byName(classOf[S_FailedUpdates].getName).id)
  def check(up: N_UpdateFrom): Boolean = !isFailedUpdates(up)
  //
  def toFailedUpdates(ev: RawEvent, reason: String): List[N_Update] = {
    val item = S_FailedUpdates(
      srcId = idGenUtil.srcIdRandom(), txId = ev.srcId, reason = reason, at = System.currentTimeMillis(),
      electorClientId = currentProcess.id, materialized = false,
    )
    LEvent.update(item).map(toUpdate.toUpdate).toList
  }
  //
  def preparePrevTxFailed(local: Context): Context =
    if(getFailedTx.ofA(local).contains(ReadAfterWriteOffsetKey.of(local))) PrevTxFailedKey.modify(_+1L)(local)
    else local
  private def max(a: Instant, b: Instant): Instant = if(a.isAfter(b)) a else b
  def handlePrevTxFailed(local: Context): Context = PrevTxFailedKey.of(local) match {
    case 0L => local case n => SleepUntilKey.modify(max(_, Instant.now.plusSeconds(n)))(local)
  }
}

@c4assemble("ServerCompApp") class FailureAssembleBase(
  materializeFailuresTxFactory: MaterializeFailuresTxFactory, classifyFailuresTxFactory: ClassifyFailuresTxFactory
){
  type ByTxId = SrcId
  def mapByTxId(key: SrcId, up: Each[S_FailedUpdates]): Values[(ByTxId, S_FailedUpdates)] = Seq(up.txId -> up)
  def joinByTxId(key: SrcId, @by[ByTxId] updates: Values[S_FailedUpdates]): Values[(SrcId,FailedTx)] =
    Seq(WithPK(FailedTx(key, updates.sortBy(_.srcId).toList)))
  //
  type ByMaterialize = SrcId
  def mapByMaterialize(key: SrcId, up: Each[S_FailedUpdates]): Values[(ByMaterialize, S_FailedUpdates)] =
    if(up.materialized) Nil else Seq("MaterializeFailedUpdates" -> up)
  def joinByMaterialize(key: SrcId, @by[ByMaterialize] updates: Values[S_FailedUpdates]): Values[(SrcId,EnabledTxTr)] =
    EnabledTxTr(materializeFailuresTxFactory.create(key, updates.sortBy(_.srcId).toList))
  //
  def mapUnclassified(
    key: SrcId, tx: Each[FailedTx],
    consistent: Values[S_ConsistentlyFailedTx], inconsistent: Values[S_InconsistentlyFailedTx],
  ): Values[(SrcId, UnclassifiedFailedTx)] =
    if(consistent.nonEmpty || inconsistent.nonEmpty) Nil else Seq(WithPK(UnclassifiedFailedTx(tx)))
  def classify(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId, TxTransform)] =
    Seq(WithPK(classifyFailuresTxFactory.create()))
}

@c4multi("ServerCompApp") final case class MaterializeFailuresTx(srcId: SrcId, updates: List[S_FailedUpdates])(
  txAdd: LTxAdd,
) extends TxTransform {
  def transform(local: Context): Context = {
    val lEvents = updates.map(_.copy(materialized = true)).flatMap(LEvent.update)
    txAdd.add(lEvents)(local)
  }
}

@c4multi("ServerCompApp") final case class ClassifyFailuresTx(srcId: SrcId = "ClassifyFailedUpdates")(
  txAdd: LTxAdd, getReadyProcesses: GetByPK[ReadyProcesses], actorName: ActorName,
  getUnclassifiedFailedTx: GetByPK[UnclassifiedFailedTx],
) extends TxTransform {
  def transform(local: Context): Context = {
    val until = System.currentTimeMillis() - 30 * 1000
    val lEvents = getUnclassifiedFailedTx.ofA(local).values.toList.map(_.tx).sortBy(_.txId).flatMap{ tx =>
      val processes = getReadyProcesses.ofA(local).get(actorName.value).fold(List.empty[ReadyProcess])(_.all).map(_.id)
      if(processes.forall(tx.updates.map(_.electorClientId).toSet)) LEvent.update(S_ConsistentlyFailedTx(tx.txId))
      else if(tx.updates.forall(_.at < until)) LEvent.update(S_InconsistentlyFailedTx(tx.txId)) else Nil
    }
    txAdd.add(lEvents).andThen(SleepUntilKey.set(Instant.now.plusSeconds(5)))(local)
  }
}
*/

