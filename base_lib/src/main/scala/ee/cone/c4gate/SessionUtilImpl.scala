package ee.cone.c4gate

import ee.cone.c4actor.{AssembledContext, Context, GetByPK, IdGenUtil, LEvent, ListConfig}
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import ee.cone.c4gate.AlienProtocol.{N_FromAlienWish, U_FromAlienState, U_FromAlienStatus, U_FromAlienWishes, U_ToAlienAck}
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.N_Header

import java.time.Instant
import scala.annotation.tailrec

@c4("SessionUtilApp") final class SessionUtilImpl(
  idGenUtil: IdGenUtil, eventLogUtil: EventLogUtil, listConfig: ListConfig,
  getAuthenticatedSession: GetByPK[U_AuthenticatedSession],
  getFromAlienState: GetByPK[U_FromAlienState],
  getFromAlienStatus: GetByPK[U_FromAlienStatus],
) extends SessionUtil {
  private val sessionTimeoutSec = Single.option(listConfig.get("C4STATE_REFRESH_SECONDS")).fold(100L)(_.toLong)
  def create(userName: String, headers: List[N_Header]): (String, LEvents) = {
    val (logKey, logEvents) = eventLogUtil.create()
    val sessionKey = idGenUtil.srcIdRandom()
    val until = Instant.now.plusSeconds(20).getEpochSecond
    val session = U_AuthenticatedSession(sessionKey, userName, until, headers, logKey)
    val state = U_FromAlienState(sessionKey, "", Option(userName).filter(_.nonEmpty))
    val status = U_FromAlienStatus(sessionKey, until, isOnline = true)
    (sessionKey, LEvent.update(Seq(session, state, status)) ++ logEvents)
  }
  def purge(local: Context, sessionKey: String): LEvents = {
    def rm[T<:Product](get: GetByPK[T]): LEvents = get.ofA(local).get(sessionKey).toSeq.flatMap(LEvent.delete)
    val sessionOpt = getAuthenticatedSession.ofA(local).get(sessionKey)
    val logEvents = sessionOpt.toSeq.flatMap(s=>eventLogUtil.purgeAll(local,s.logKey))
    logEvents ++ rm(getAuthenticatedSession) ++ rm(getFromAlienState) ++ rm(getFromAlienStatus)
  }
  def location(local: Context, sessionKey: String): String = getFromAlienState.ofA(local)(sessionKey).location
  def setLocation(local: Context, sessionKey: String, value: String): LEvents =
    LEvent.update(getFromAlienState.ofA(local)(sessionKey).copy(location = value))
  def trySetStatus(world: AssembledContext, sessionKey: String, isOnline: Boolean): LEvents =
    getFromAlienStatus.ofA(world).get(sessionKey).toSeq.flatMap(s => LEvent.update(s.copy(
      expirationSecond = Instant.now.plusSeconds(sessionTimeoutSec).getEpochSecond, isOnline = isOnline
    )))
  def expired(local: Context, sessionKey: String): Boolean =
    getFromAlienStatus.ofA(local).get(sessionKey).forall(_.expirationSecond < Instant.now.getEpochSecond)
}

@c4("SessionUtilApp") final class FromAlienWishUtilImpl(
  idGenUtil: IdGenUtil, getToAlienAck: GetByPK[U_ToAlienAck], getFromAlienWishes: GetByPK[U_FromAlienWishes],
) extends FromAlienWishUtil {
  def ack(local: Context, branchKey: String, sessionKey: String): Long =
    getToAlienAck.ofA(local).get(pk(branchKey, sessionKey)).fold(0L)(_.index)
  def addAck(branchKey: String, sessionKey: String, index: Long): LEvents =
    LEvent.update(U_ToAlienAck(pk(branchKey, sessionKey), branchKey, sessionKey, index))
  def addWishes(world: AssembledContext, branchKey: String, sessionKey: String, values: List[N_FromAlienWish]): LEvents = {
    val wishes = U_FromAlienWishes(pk(branchKey, sessionKey), branchKey, sessionKey, values)
    if (getFromAlienWishes.ofA(world).get(wishes.srcId).contains(wishes)) Nil else LEvent.update(wishes)
  }
  private def pk(branchKey: String, sessionKey: String): SrcId = idGenUtil.srcIdFromStrings(branchKey, sessionKey)
}