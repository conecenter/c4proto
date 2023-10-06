package ee.cone.c4actor

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.NoJoiningProfiling.Res
import ee.cone.c4actor.QProtocol.{N_TxRef, N_Update}
import ee.cone.c4actor.SimpleAssembleProfilerProtocol.{D_LogEntry, D_TxAddMeta}
import ee.cone.c4assemble.Types.DPIterable
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types._
import ee.cone.c4di.{c4, provide}
import ee.cone.c4proto.{Id, protocol}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

@c4("NoAssembleProfilerCompApp") final class NoAssembleProfilerProvider {
  @provide def get: Seq[AssembleProfiler] = List(NoAssembleProfiler)
}

case object NoAssembleProfiler extends AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]): JoiningProfiling =
    NoJoiningProfiling
  def addMeta(transition: WorldTransition, updates: Seq[N_Update]): Seq[N_Update] =
    updates
}

case object NoJoiningProfiling extends JoiningProfiling {
  def time: Long = 0L
  def handle(join: Join, result: Seq[AggrDOut], wasLog: ProfilingLog): ProfilingLog = wasLog
  def handle(join: Join, stage: Res, start: Res, wasLog: ProfilingLog): ProfilingLog = wasLog
}

////

@protocol("SimpleAssembleProfilerCompApp") object SimpleAssembleProfilerProtocol   {
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

@c4("SimpleAssembleProfilerCompApp") final case class SimpleAssembleProfiler(idGenUtil: IdGenUtil)(toUpdate: ToUpdate) extends AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]) =
    if(localOpt.isEmpty) SimpleConsoleSerialJoiningProfiling
    else SimpleSerialJoiningProfiling(System.nanoTime)
  def addMeta(transition: WorldTransition, updates: Seq[N_Update]): Seq[N_Update] = transition.profiling match {
    case SimpleSerialJoiningProfiling(startedAt) =>
    //val meta = transition.profiling.result.toList.flatMap(LEvent.update).map(toUpdate.toUpdate)
    val finishedAt = System.nanoTime
    val size = updates.map(_.value.size).sum
    val types = updates.map(_.valueTypeId).distinct.toList
    val id = idGenUtil.srcIdFromStrings(UUID.randomUUID.toString)
    val log = transition.log.collect{ case l: D_LogEntry => l }
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

case class SimpleSerialJoiningProfiling(startedAt: Long) extends JoiningProfiling {
  def time: Long = System.nanoTime
  def handle(join: Join, stage: Long, start: Long, wasLog: ProfilingLog): ProfilingLog = {
    val period = (System.nanoTime - start) / 1000
    D_LogEntry(join.name,stage,period) :: wasLog
  }
  def handle(join: Join, result: Seq[AggrDOut], wasLog: ProfilingLog): ProfilingLog = wasLog
}

case object SimpleConsoleSerialJoiningProfiling extends JoiningProfiling with LazyLogging {
  def time: Long = System.nanoTime
  def handle(join: Join, stage: Long, start: Long, wasLog: ProfilingLog): ProfilingLog = {
    val period = (System.nanoTime - start) / 1000000
    if(period > 50)
      logger.debug(s"$period ms for ${join.name}-$stage") // "${joinRes.size} items"
    wasLog
  }
  def handle(join: Join, result: Seq[AggrDOut], wasLog: ProfilingLog): ProfilingLog = wasLog
}
