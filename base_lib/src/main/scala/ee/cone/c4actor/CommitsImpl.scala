package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_UpdateFrom, S_Firstborn, S_TxCommitReq}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.{c4, c4multi}
import okio.ByteString

import java.time.Instant


///

@c4("RichDataCompApp") final class CommitsImpl(
  qAdapterRegistry: QAdapterRegistry, idGenUtil: IdGenUtil, toUpdate: ToUpdate, updateMapUtil: UpdateMapUtil,
  getCommitReq: GetByPK[S_TxCommitReq], execution: Execution,
) extends Commits with LazyLogging {
  private val commitReqTypeId = qAdapterRegistry.byName(classOf[S_TxCommitReq].getName).id
  def isCommitReq(up: N_UpdateFrom): Boolean = up.valueTypeId == commitReqTypeId
  def addCommitReq(updates: List[N_UpdateFrom]): List[N_UpdateFrom] =
    if(updates.isEmpty || updates.exists(isCommitReq)) updates else {
      val req = S_TxCommitReq(idGenUtil.srcIdRandom(),"",System.currentTimeMillis)
      LEvent.update(req).map(toUpdate.toUpdate).map(updateMapUtil.insert).toList ++ updates
    }
  def commit(local: Context): Seq[LEvent[Product]] = LEvent.delete(getCommitReq.ofA(local).values.toSeq.sortBy(_.txId))
  def fail(hint: String): Unit = {
    logger.error(hint)
    execution.complete()
  }

  // this throws if assemble succeeds on follower but not master
  def check(local: Context): Unit =
    for(req <- getCommitReq.ofA(local).values.minByOption(_.txId) if System.currentTimeMillis-req.time>60_000)
      fail(s"world split for commit ${req.txId}")

  // this throws if assemble succeeds on master but not follower
  def check(up: N_UpdateFrom): Unit = if(isCommitReq(up)) fail("world split")
}

@c4assemble("ServerCompApp") class CommitsAssembleBase(
  committingTxFactory: CommittingTxFactory, commitCheckingTxFactory: CommitCheckingTxFactory
) {
  def committing(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId,TxTransform)] =
    Seq(WithPK(committingTxFactory.create()))
  def checking(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId,EnabledTxTr)] =
    Seq(WithPK(EnabledTxTr(commitCheckingTxFactory.create())))
}
@c4multi("ServerCompApp") final case class CommittingTx(srcId: SrcId="CommittingTx")(
  commits: CommitsImpl, txAdd: LTxAdd
) extends TxTransform {
  def transform(local: Context): Context =
    txAdd.add(commits.commit(local)).andThen(SleepUntilKey.set(Instant.now.plusSeconds(5)))(local)
}
@c4multi("ServerCompApp") final case class CommitCheckingTx(srcId: SrcId="CommitCheckingTx")(
  commits: CommitsImpl
) extends TxTransform {
  def transform(local: Context): Context = {
    commits.check(local)
    SleepUntilKey.set(Instant.now.plusSeconds(5))(local)
  }
}


////

object Commits {
  private val header: RawHeader = RawHeader("c","rc")
  def partition(events: Seq[RawEvent]): (Seq[RawEvent], Seq[RawEvent]) = {
    val resolutions = events.flatMap(ev =>
      if(!ev.headers.contains(header)) Nil
      else Seq(ev.srcId->"r") ++ ev.data.utf8().split(":").grouped(2).map{ case Array(txn,res) => (txn,res) }
    ).toMap
    val (resolvedEvents, unresolvedEvents) = events.partition(ev => resolutions.contains(ev.srcId))
    for(r <- resolvedEvents.lastOption; u <- unresolvedEvents.headOption if r.srcId >= u.srcId) throw new Exception("bad check")
    (resolvedEvents.map(ev => resolutions(ev.srcId) match {
      case "c" => ev case "r" => SimpleRawEvent(ev.srcId, ByteString.EMPTY, Nil)
    }), unresolvedEvents)
  }

  def assemble(wasUnchecked: Seq[(String,String)], events: Seq[RawEvent], expect: String): (Seq[(String,String)], Seq[RawEvent]) = {


  }

}

final class CommittedConsumer(inner: Consumer) extends Consumer {
  private var keepEvents = Seq.empty[RawEvent]
  def poll(): List[RawEvent] = {
    val (resolvedEvents, unresolvedEvents) = Commits.partition(keepEvents ++ inner.poll())
    keepEvents = unresolvedEvents
    resolvedEvents.toList
  }
  def endOffset: NextOffset = inner.endOffset
}

