package ee.cone.c4actor

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{TxRef, Update}
import ee.cone.c4actor.SimpleAssembleProfilerProtocol.{LogEntry, TxAddMeta}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.DPIterable
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.{Id, Protocol, protocol}
import okio.ByteString

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.Duration

case object NoAssembleProfiler extends AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]): JoiningProfiling =
    NoJoiningProfiling
  def addMeta(transition: WorldTransition, updates: Seq[Update]): Seq[Update] =
    updates
}

case object NoJoiningProfiling extends JoiningProfiling {
  def time: Long = 0L
  def handle(
    join: Join,
    calcStart: Long, findChangesStart: Long, patchStart: Long,
    joinRes: DPIterable[Index]
  ): ProfilingLog = Nil
}

////

@protocol object SimpleAssembleProfilerProtocol extends Protocol {
  @Id(0x0073) case class TxAddMeta(
    @Id(0x0074) srcId: String,
    @Id(0x0075) startedAt: Long,
    @Id(0x0076) finishedAt: Long,
    @Id(0x0077) log: List[LogEntry],
    @Id(0x007B) updObjCount: Long,
    @Id(0x007C) updByteCount: Long,
    @Id(0x007D) updValueTypeIds: List[Long]
  )
  @Id(0x0078) case class LogEntry(
    @Id(0x0079) name: String,
    @Id(0x007A) value: Long
  )
}

case class SimpleAssembleProfiler(idGenUtil: IdGenUtil)(toUpdate: ToUpdate) extends AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]) =
    if(localOpt.isEmpty) SimpleConsoleSerialJoiningProfiling
    else SimpleSerialJoiningProfiling(System.nanoTime)
  def addMeta(transition: WorldTransition, updates: Seq[Update]): Seq[Update] = transition.profiling match {
    case SimpleSerialJoiningProfiling(startedAt) ⇒
    //val meta = transition.profiling.result.toList.flatMap(LEvent.update).map(toUpdate.toUpdate)
    val finishedAt = System.nanoTime
    val size = updates.map(_.value.size).sum
    val types = updates.map(_.valueTypeId).distinct.toList
    val id = idGenUtil.srcIdFromStrings(UUID.randomUUID.toString)
    val log = Await.result(transition.log, Duration.Inf).collect{
      case l: LogEntry ⇒ l
    }
    val meta = List(
      TxRef(id,""),
      TxAddMeta(id,startedAt,finishedAt,log,updates.size,size,types)
    )
    meta.flatMap(LEvent.update).map(toUpdate.toUpdate) ++ updates
  }
}

case class SimpleSerialJoiningProfiling(startedAt: Long) extends JoiningProfiling {
  def time: Long = System.nanoTime
  def handle(
    join: Join,
    calcStart: Long,
    findChangesStart: Long,
    patchStart: Long,
    joinRes: DPIterable[Index]
  ): ProfilingLog = {
    val period = (System.nanoTime - calcStart) / 1000
    LogEntry(join.name,period) :: Nil
  }
}

case object SimpleConsoleSerialJoiningProfiling extends JoiningProfiling with LazyLogging {
  def time: Long = System.nanoTime
  def handle(
    join: Join,
    calcStart: Long,
    findChangesStart: Long,
    patchStart: Long,
    joinRes: DPIterable[Index]
  ): ProfilingLog = {
    val period = (System.nanoTime - calcStart) / 1000000
    if(period > 50)
      logger.debug(s"$period ms ${joinRes.size} items for ${join.name}")
    Nil
  }
}
