package ee.cone.c4actor

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_TxRef, N_Update}
import ee.cone.c4actor.SimpleAssembleProfilerProtocol.{D_LogEntry, D_TxAddMeta}
import ee.cone.c4assemble.Types.DPIterable
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.{Id, protocol}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

case object NoAssembleProfiler extends AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]): JoiningProfiling =
    NoJoiningProfiling
  def addMeta(transition: WorldTransition, updates: Seq[N_Update]): Future[Seq[N_Update]] =
    Future.successful(updates)
}

case object NoJoiningProfiling extends JoiningProfiling {
  def time: Long = 0L
  def handle(join: Join, stage: Long, start: Long, joinRes: Res, wasLog: ProfilingLog): ProfilingLog = Nil
}

////

@protocol object SimpleAssembleProfilerProtocolBase   {
  @Id(0x0073) case class D_TxAddMeta(
    @Id(0x0074) srcId: String,
    @Id(0x0075) startedAt: Long,
    @Id(0x0076) finishedAt: Long,
    @Id(0x0077) log: List[D_LogEntry],
    @Id(0x007B) updObjCount: Long,
    @Id(0x007C) updByteCount: Long,
    @Id(0x007D) updValueTypeIds: List[Long]
  )
  @Id(0x0078) case class D_LogEntry(
    @Id(0x0079) name: String,
    @Id(0x007E) stage: Long,
    @Id(0x007A) value: Long
  )
}

case class SimpleAssembleProfiler(idGenUtil: IdGenUtil)(toUpdate: ToUpdate) extends AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]) =
    if(localOpt.isEmpty) SimpleConsoleSerialJoiningProfiling
    else SimpleSerialJoiningProfiling(System.nanoTime)
  def addMeta(transition: WorldTransition, updates: Seq[N_Update]): Future[Seq[N_Update]] = transition.profiling match {
    case SimpleSerialJoiningProfiling(startedAt) ⇒
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    //val meta = transition.profiling.result.toList.flatMap(LEvent.update).map(toUpdate.toUpdate)
    val finishedAt = System.nanoTime
    val size = updates.map(_.value.size).sum
    val types = updates.map(_.valueTypeId).distinct.toList
    val id = idGenUtil.srcIdFromStrings(UUID.randomUUID.toString)
    for {
      logAll ← transition.log
    } yield {
      val log = logAll.collect{ case l: D_LogEntry ⇒ l }
      val meta = List(
        N_TxRef(id,""),
        D_TxAddMeta(id,startedAt,finishedAt,log,updates.size,size,types)
      )
      val metaUpdates = meta.flatMap(LEvent.update).map(toUpdate.toUpdate)
      val metaTypeIds = metaUpdates.map(_.valueTypeId).toSet
      if(updates.map(_.valueTypeId).forall(metaTypeIds)) updates
      else metaUpdates ++ updates
    }
  }
}

case class SimpleSerialJoiningProfiling(startedAt: Long) extends JoiningProfiling {
  def time: Long = System.nanoTime
  def handle(join: Join, stage: Long, start: Long, joinRes: Res, wasLog: ProfilingLog): ProfilingLog = {
    val period = (System.nanoTime - start) / 1000
    D_LogEntry(join.name,stage,period) :: wasLog
  }
}

case object SimpleConsoleSerialJoiningProfiling extends JoiningProfiling with LazyLogging {
  def time: Long = System.nanoTime
  def handle(join: Join, stage: Long, start: Long, joinRes: Res, wasLog: ProfilingLog): ProfilingLog = {
    val period = (System.nanoTime - start) / 1000000
    if(period > 50)
      logger.debug(s"$period ms for ${join.name}-$stage") // "${joinRes.size} items"
    wasLog
  }
}
