package ee.cone.c4gate_server
import ee.cone.c4actor.{GetByPK, RichContext, WorldSource}
import ee.cone.c4di.c4
import ee.cone.c4gate.{ByPathHttpPublicationUntil, EventLogUtil}
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec

@c4("AbstractHttpGatewayApp") final class EventLogReaderImpl(
  eventLogUtil: EventLogUtil, worldSource: WorldSource, getPublication: GetByPK[ByPathHttpPublicationUntil],
) extends EventLogReader {
  def read(logKey: String, pos: Long): (Long, String) = {
    val queue = new LinkedBlockingQueue[Either[RichContext, Unit]]()
    val until = Instant.now.plusSeconds(1)
    @tailrec def iteration(): (Long, String) = {
      val Left(world) = queue.take()
      val readRes = eventLogUtil.read(world, logKey, pos)
      val now = Instant.now
      if (readRes.events.nonEmpty || now.isAfter(until)) {
        val availability = getPublication.ofA(world).get("/availability").fold(0L)(_.until - now.toEpochMilli)
        val message = s"""{"availability":$availability,"log":[${readRes.events.filter(_.nonEmpty).mkString(",")}]}"""
        (readRes.next, message)
      } else iteration()
    }
    worldSource.doWith(queue, iteration)
  }
}
