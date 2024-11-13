package ee.cone.c4gate_server

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.LEvents
import ee.cone.c4actor.{AssembledContext, GetByPK, ListConfig}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import ee.cone.c4gate.{ByPathHttpPublicationUntil, EventLogUtil, FromAlienWishUtil, SessionUtil}

import java.time.Instant

case class AlienExchangeStateImpl(
  isMain: Boolean, branchKey: String, sessionKey: String, pos: Long
) extends AlienExchangeState

@c4("AbstractHttpGatewayApp") final class AlienUtilImpl(
  worldProvider: WorldProvider, getPublication: GetByPK[ByPathHttpPublicationUntil],
  eventLogUtil: EventLogUtil, fromAlienWishUtil: FromAlienWishUtil, sessionUtil: SessionUtil, listConfig: ListConfig,
) extends AlienUtil with LazyLogging {
  import WorldProvider._
  import worldProvider._
  private val sessionTimeoutSec = Single.option(listConfig.get("C4STATE_REFRESH_SECONDS")).fold(100L)(_.toLong)
  def read(state: AlienExchangeState): (AlienExchangeState, String) = {
    val until = Instant.now.plusSeconds(1)
    run(List(world => {
      val st = state match { case s: AlienExchangeStateImpl => s }
      val readRes = eventLogUtil.read(world, st.branchKey, st.pos)
      val now = Instant.now
      if (readRes.events.nonEmpty || now.isAfter(until)) {
        val availability = getPublication.ofA(world).get("/availability").fold(0L)(_.until - now.toEpochMilli)
        val log = readRes.events.filter(_.nonEmpty).mkString("[",",","]")
        val ack = fromAlienWishUtil.ack(world, st.branchKey, st.sessionKey)
        val message = s"""{"availability":$availability,"log":$log,"ack":$ack}"""
        Stop((st.copy(pos=readRes.next), message))
      } else Redo()
    }):Steps[(AlienExchangeState, String)])
  }
  private def prepStatus(state: AlienExchangeState, isOnline: Boolean): AssembledContext=>LEvents = {
    val st = state match { case s: AlienExchangeStateImpl => s }
    if(st.isMain){
      val until = Instant.now.plusSeconds(sessionTimeoutSec).getEpochSecond
      world => sessionUtil.trySetStatus(world, st.sessionKey, until, isOnline)
    } else _=>Nil
  }
  def send(value: String): AlienExchangeState = {
    val Seq("bs1", modeStr, branchKey, sessionKey, patches) = fromAlienWishUtil.parseSeq(value)
    val isMain = modeStr match { case "m" => true case "s" => false }
    val willState = AlienExchangeStateImpl(isMain, branchKey, sessionKey, 0L)
    val setStatus = prepStatus(willState, isOnline = true)
    runUpdCheck(world => setStatus(world) ++ fromAlienWishUtil.setWishes(world, branchKey, sessionKey, patches))
    willState
  }
  def stop(state: AlienExchangeState): Unit = {
    val setStatus = prepStatus(state, isOnline = false)
    runUpdCheck(world => setStatus(world))
  }
}
