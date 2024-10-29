package ee.cone.c4gate_server
import ee.cone.c4actor.GetByPK
import ee.cone.c4di.c4
import ee.cone.c4gate.{ByPathHttpPublicationUntil, EventLogUtil}

import java.time.Instant
import scala.concurrent.Future

@c4("AkkaGatewayApp") final class AsyncEventLogReaderImpl(
  eventLogUtil: EventLogUtil, worldSource: WorldSource, getPublication: GetByPK[ByPathHttpPublicationUntil],
) extends AsyncEventLogReader {
  def read(logKey: String, pos: Long): Future[(Long, String)] = {
    val until = Instant.now.plusSeconds(1)
    worldSource.take { world =>
      val readRes = eventLogUtil.read(world, logKey, pos)
      val now = Instant.now
      if (readRes.events.nonEmpty || now.isAfter(until)) {
        val availability = getPublication.ofA(world).get("/availability").fold(0L)(_.until - now.toEpochMilli)
        val message = s"""{"availability":$availability,"log":[${readRes.events.filter(_.nonEmpty).mkString(",")}]}"""
        Option((readRes.next, message))
      } else None
    }
  }
}