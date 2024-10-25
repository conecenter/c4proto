package ee.cone.c4gate

import ee.cone.c4actor.{Context, GetByPK, IdGenUtil, LEvent, WithPK}
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4
import ee.cone.c4gate.AlienProtocol.{U_ToAlienAck, U_FromAlienState, U_FromAlienStatus}
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol.N_Header

import java.time.Instant

@c4("SessionAttrCompApp") class SessionUtilImpl(
  idGenUtil: IdGenUtil, eventLogUtil: EventLogUtil,
  getAuthenticatedSession: GetByPK[U_AuthenticatedSession],
  getFromAlienState: GetByPK[U_FromAlienState],
  getFromAlienStatus: GetByPK[U_FromAlienStatus],
  getToAlienAck: GetByPK[U_ToAlienAck],
) extends SessionUtil {
  def create(userName: String, headers: List[N_Header]): (String, LEvents) = {
    val (logKey, logEvents) = eventLogUtil.create()
    val sessionKey = idGenUtil.srcIdRandom()
    val until = Instant.now.plusSeconds(20).getEpochSecond
    val session = U_AuthenticatedSession(sessionKey, userName, until, headers, logKey)
    val state = U_FromAlienState(sessionKey, "", Option(userName).filter(_.nonEmpty))
    val status = U_FromAlienStatus(sessionKey, until, isOnline = true)
    val ack = U_ToAlienAck(sessionKey, Nil)
    (sessionKey, LEvent.update(Seq(session, state, status, ack)) ++ logEvents)
  }
  def purge(local: Context, sessionKey: String): LEvents = {
    def rm[T<:Product](get: GetByPK[T]): LEvents = get.ofA(local).get(sessionKey).toSeq.flatMap(LEvent.delete)
    val sessionOpt = getAuthenticatedSession.ofA(local).get(sessionKey)
    val logEvents = sessionOpt.toSeq.flatMap(s=>eventLogUtil.purgeAll(local,s.logKey))
    logEvents ++ rm(getAuthenticatedSession) ++ rm(getFromAlienState) ++ rm(getFromAlienStatus) ++ rm(getToAlienAck)
  }
  def location(local: Context, sessionKey: String): String = getFromAlienState.ofA(local)(sessionKey).location
  def setLocation(local: Context, sessionKey: String, value: String): LEvents =
    LEvent.update(getFromAlienState.ofA(local)(sessionKey).copy(location = value))
  def setStatus(local: Context, sessionKey: String, timeoutSec: Long, isOnline: Boolean): LEvents = {
    val until = Instant.now.plusSeconds(timeoutSec).getEpochSecond
    assert(getFromAlienStatus.ofA(local).contains(sessionKey))
    LEvent.update(U_FromAlienStatus(sessionKey, until, isOnline))
  }
  def ackList(local: Context, sessionKey: String): List[N_Header] = getToAlienAck.ofA(local)(sessionKey).values
  def addAck(local: Context, sessionKey: String, clientKey: String, index: String): LEvents = {
    assert(clientKey.nonEmpty)
    val keep = ackList(local, sessionKey).filter(_.key != clientKey)
    LEvent.update(U_ToAlienAck(sessionKey, N_Header(clientKey, index)::keep))
  }
  def expired(local: Context, sessionKey: String): Boolean =
    getFromAlienStatus.ofA(local).get(sessionKey).forall(_.expirationSecond < Instant.now.getEpochSecond)
}
