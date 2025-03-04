package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import ee.cone.c4actor.QProtocol.{N_UpdateFrom, S_Firstborn, S_TxCommitReq}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{HasId, ProtoAdapter}
import okio.ByteString

@c4("RichDataCompApp") final class CommitsImpl(
  qAdapterRegistry: QAdapterRegistry, idGenUtil: IdGenUtil, toUpdate: ToUpdate, updateMapUtil: UpdateMapUtil,
  getCommitReq: GetByPK[S_TxCommitReq], execution: Execution,
) extends Commits with LazyLogging {
  private val commitReqAdapter =
    qAdapterRegistry.byName(classOf[S_TxCommitReq].getName).asInstanceOf[ProtoAdapter[S_TxCommitReq] with HasId]
  private val commitReqTypeId = commitReqAdapter.id
  private def isCommitReq(up: N_UpdateFrom): Boolean = up.valueTypeId == commitReqTypeId

  ///
  def addCommitReq(updates: List[N_UpdateFrom]): List[N_UpdateFrom] =
    if(updates.isEmpty || updates.exists(isCommitReq)) updates else {
      val req = S_TxCommitReq(idGenUtil.srcIdRandom(),"")
      LEvent.update(req).map(toUpdate.toUpdate).map(updateMapUtil.insert).toList ::: updates
    }
  def commit(local: Context): Seq[LEvent[Product]] = LEvent.delete(getCommitReq.ofA(local).values.toSeq.sortBy(_.txId))

  ///
  private def fail(hint: String): Unit = {
    logger.error(hint)
    execution.complete()
  }

  // this throws if assemble succeeds on follower but not master
  private def maxUncommittedTx(local: AssembledContext) = getCommitReq.ofA(local).values.map(_.txId).maxOption
  def check(before: AssembledContext, after: AssembledContext): Unit =
    for(bTn <- maxUncommittedTx(before); aTn <- maxUncommittedTx(after) if bTn > aTn)
      fail(s"world split for commit $bTn")

  // this throws if assemble succeeds on master but not follower
  def check(up: N_UpdateFrom): Unit = if(isCommitReq(up)) fail("world split")

  ///

  def toIgnorableEvents(events: Seq[RawEvent]): Seq[IgnorableEv] = events.map { ev =>
    val updates = toUpdate.toUpdates(ev::Nil, "part")
    val request = Single.option(updates.filter(isCommitReq).filter(_.value.size>0).map(_.srcId))
    val commits = updates.filter(isCommitReq).filter(_.fromValue.size>0).map(_.srcId)
    IgnorableEvImpl(ev.srcId, updates, request, commits)
  }
  def partition(events: Seq[IgnorableEv]): (Seq[IgnorableEv], Seq[IgnorableEv]) = {
    val allEvents = events.map{ case ev: IgnorableEvImpl => ev }
    val commits = allEvents.flatMap(_.commits).toSet
    def committed(ev: IgnorableEvImpl) = ev.request.forall(commits)
    val maxCommittedTxN = allEvents.filter(_.request.nonEmpty).filter(committed).map(_.srcId).maxOption
    val (resolvedEvents, unresolvedEvents) = allEvents.span(ev => committed(ev) || maxCommittedTxN.exists(ev.srcId <= _))
    (resolvedEvents.map(ev => if(committed(ev)) ev else ev.copy(updates = Nil)), unresolvedEvents)
  }
}

case class IgnorableEvImpl(srcId: SrcId, updates: Seq[N_UpdateFrom], request: Option[String], commits: Seq[String])
  extends IgnorableEv

@c4assemble("ServerCompApp") class CommitsAssembleBase(committingTxFactory: CommittingTxFactory) {
  def committing(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId,TxTransform)] =
    Seq(WithPK(committingTxFactory.create()))
}
@c4multi("ServerCompApp") final case class CommittingTx(srcId: SrcId="CommittingTx")(
  commits: CommitsImpl, txAdd: LTxAdd
) extends TxTransform {
  def transform(local: Context): Context =
    txAdd.add(commits.commit(local)).andThen(SleepUntilKey.set(Instant.now.plusSeconds(5)))(local)
}



////
/*

  class UncommittedState(val events: Seq[Ev])
  class Ev(val srcId: SrcId, val updates: Seq[N_UpdateFrom], val commits: Seq[String])
  def partition(was: UncommittedState, events: Seq[RawEvent]): (Seq[(String,Seq[N_UpdateFrom])], UncommittedState) = {
    val allEvents = was.events ++ events.map{ ev =>
      val updates = toUpdate.toUpdates(ev::Nil, "part")
      val commits = updates.collect{
        case u if isCommitReq(u) && u.value.size == 0 => commitReqAdapter.decode(u.fromValue.toByteArray).txId
      }.filter(_.nonEmpty)
      new Ev(ev.srcId, updates, commits)
    }
    val commits = allEvents.flatMap(_.commits).toSet
    val maxCommit = commits.maxOption
    val (resolvedEvents, unresolvedEvents) = allEvents.span(ev => maxCommit.exists(ev.srcId<=_) || ev.commits.nonEmpty)
    val cleanEvents = resolvedEvents.map(ev => (ev.srcId, if(commits(ev.srcId) || ev.commits.nonEmpty) ev.updates else Nil))
    (cleanEvents, new UncommittedState(unresolvedEvents))
  }


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

*/