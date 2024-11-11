package ee.cone.c4gate_server

import ee.cone.c4actor.Types.LEvents
import ee.cone.c4actor.{AssembledContext, GetByPK, ListConfig}
import ee.cone.c4assemble.Single
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.{ByPathHttpPublicationUntil, EventLogUtil, FromAlienWishUtil, SessionUtil}

import java.time.Instant

@c4("AbstractHttpGatewayApp") final class AlienConnectionFactoryImpl(
  inner: AlienConnectionImplFactory
) extends AlienConnectionFactory {
  def create(value: String): AlienConnection = {
    val Array("ee","cone","c4bs1",modeStr,logKey,sessionKey) = value.split("\\.")
    val isMain = modeStr match { case "m" => true case "s" => false }
    inner.create(isMain, logKey, sessionKey)
  }
}

@c4multi("AbstractHttpGatewayApp") final class AlienConnectionImpl(
  isMain: Boolean, branchKey: String, sessionKey: String
)(
  worldProvider: WorldProvider, getPublication: GetByPK[ByPathHttpPublicationUntil],
  eventLogUtil: EventLogUtil, fromAlienWishUtil: FromAlienWishUtil, sessionUtil: SessionUtil, listConfig: ListConfig,
) extends AlienConnection {
  import WorldProvider._
  import worldProvider._
  private val sessionTimeoutSec = Single.option(listConfig.get("C4STATE_REFRESH_SECONDS")).fold(100L)(_.toLong)
  def read(pos: Long): (Long, String) = {
    val until = Instant.now.plusSeconds(1)
    run(List(world => {
      val readRes = eventLogUtil.read(world, branchKey, pos)
      val now = Instant.now
      if (readRes.events.nonEmpty || now.isAfter(until)) {
        val availability = getPublication.ofA(world).get("/availability").fold(0L)(_.until - now.toEpochMilli)
        val log = readRes.events.filter(_.nonEmpty).mkString("[",",","]")
        val ack = fromAlienWishUtil.ack(world, branchKey, sessionKey)
        val message = s"""{"availability":$availability,"log":$log,"ack":$ack}"""
        Stop((readRes.next, message))
      } else Redo()
    }):Steps[(Long, String)])
  }
  private def prepStatus(isOnline: Boolean): AssembledContext=>LEvents =
    if(isMain){
      val until = Instant.now.plusSeconds(sessionTimeoutSec).getEpochSecond
      world => sessionUtil.trySetStatus(world, sessionKey, until, isOnline)
    } else _=>Nil

  def send(value: String): Unit = if(value.nonEmpty){
    val setStatus = prepStatus(true)
    runUpdCheck(world => setStatus(world) ++ fromAlienWishUtil.setWishes(world, branchKey, sessionKey, value))
  }
  def stop(): Unit = {
    val setStatus = prepStatus(false)
    runUpdCheck(world => setStatus(world))
  }
}
