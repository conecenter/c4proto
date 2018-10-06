package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.SimpleAssembleProfilerProtocol.{LogEntry, TxAddMeta}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.DPIterable
import ee.cone.c4assemble.{Join, SerialJoiningProfiling, WorldTransition}
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable.Seq

trait AssemblerProfilerApp {
  def assembleProfiler: AssembleProfiler
}

case object NoAssembleProfiler extends AssembleProfiler {
  def createSerialJoiningProfiling(localOpt: Option[Context]): SerialJoiningProfiling =
    NoSerialJoiningProfiling
  def addMeta(profiling: SerialJoiningProfiling, updates: Seq[Update]): Seq[Update] =
    updates
}

case object NoSerialJoiningProfiling extends SerialJoiningProfiling {
  def time: Long = 0L
  def handle(
    join: Join,
    calcStart: Long, findChangesStart: Long, patchStart: Long,
    joinRes: DPIterable[Index],
    transition: WorldTransition
  ): WorldTransition = transition
}

////

@protocol object SimpleAssembleProfilerProtocol extends Protocol {
  @Id(0x0073) case class TxAddMeta(
    @Id(0x0074) srcId: String,
    @Id(0x0075) startedAt: Long,
    @Id(0x0076) finishedAt: Long,
    @Id(0x0077) log: List[LogEntry]
  )
  @Id(0x0078) case class LogEntry(
    @Id(0x0079) name: String,
    @Id(0x007A) value: Long
  )
}

case class SimpleAssembleProfiler(toUpdate: ToUpdate) extends AssembleProfiler {
  def createSerialJoiningProfiling(localOpt: Option[Context]) =
    if(localOpt.isEmpty) SimpleConsoleSerialJoiningProfiling
    else SimpleSerialJoiningProfiling(System.nanoTime, Nil)
  def addMeta(profiling: SerialJoiningProfiling, updates: Seq[Update]): Seq[Update] = profiling match {
    case SimpleSerialJoiningProfiling(startedAt,log) ⇒
    //val meta = transition.profiling.result.toList.flatMap(LEvent.update).map(toUpdate.toUpdate)
    val finishedAt = System.nanoTime
    val meta = TxAddMeta("-",startedAt,finishedAt,log)
    LEvent.update(meta).map(toUpdate.toUpdate) ++ updates
  }
}

case class SimpleSerialJoiningProfiling(startedAt: Long, log: List[LogEntry])
  extends SerialJoiningProfiling
{
  def time: Long = System.nanoTime
  def handle(
    join: Join,
    calcStart: Long,
    findChangesStart: Long,
    patchStart: Long,
    joinRes: DPIterable[Index],
    transition: WorldTransition
  ): WorldTransition = {
    val period = (System.nanoTime - calcStart) / 1000
    transition.copy(profiling = copy(log=LogEntry(join.name,period)::log))
  }
}

case object SimpleConsoleSerialJoiningProfiling extends SerialJoiningProfiling with LazyLogging {
  def time: Long = System.nanoTime
  def handle(
    join: Join,
    calcStart: Long,
    findChangesStart: Long,
    patchStart: Long,
    joinRes: DPIterable[Index],
    transition: WorldTransition
  ): WorldTransition = {
    val period = (System.nanoTime - calcStart) / 1000000
    if(period > 50)
      logger.debug(s"$period ms ${joinRes.size} items for ${join.name}")
    transition
  }
}
