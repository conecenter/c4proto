package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4assemble.{Join, JoiningProfiling, WorldTransition}
import ee.cone.c4assemble.Types.{DPIterable, Index, ProfilingLog}

import scala.collection.immutable.Seq
import scala.concurrent.Future

case object ConsoleAssembleProfiler extends AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]): JoiningProfiling = ConsoleProfiling

  def addMeta(transition: WorldTransition, updates: Seq[QProtocol.N_Update]): Future[Seq[N_Update]] = Future.successful(updates)
}

case object ConsoleProfiling extends JoiningProfiling with LazyLogging {
  def time: Long = System.nanoTime

  def handle(join: Join, stage: Long, start: Long, joinRes: Res, wasLog: ProfilingLog): ProfilingLog = {
    val timeNano: Long = (System.nanoTime - start) / 10000
    val timeFront: Double = timeNano / 100.0
    val countT = joinRes
    logger.debug(s"rule ${join.assembleName}-${join.name}-$stage ${getColoredCount(countT)} items for ${getColoredPeriod(timeFront)} ms")
    wasLog
  }

  def getColoredPeriod: Double => String = {
    case i if i < 200 => PrintColored.makeColored("g")(i.toString)
    case i if i >= 200 && i < 500 => PrintColored.makeColored("y")(i.toString)
    case i if i >= 500 => PrintColored.makeColored("r")(i.toString)
  }

  def getColoredCount: Long => String = {
    case i if i < 100 => PrintColored.makeColored("g")(i.toString)
    case i if i >= 100 && i < 1000 => PrintColored.makeColored("y")(i.toString)
    case i if i >= 1000 => PrintColored.makeColored("r")(i.toString)
  }

}
