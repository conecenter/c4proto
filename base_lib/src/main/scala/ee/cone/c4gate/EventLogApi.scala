package ee.cone.c4gate

import ee.cone.c4actor.AssembledContext
import ee.cone.c4actor.Types.LEvents

case class EventLogReadResult(events: Seq[String], next: Long)
trait EventLogUtil {
  def create(): (String, LEvents)
  def read(world: AssembledContext, logKey: String, pos: Long): EventLogReadResult
  def write(world: AssembledContext, logKey: String, eventValue: String, snapshotOpt: Option[String]): LEvents
  def purgeAll(world: AssembledContext, logKey: String): LEvents
}
