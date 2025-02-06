package ee.cone.c4gate

import ee.cone.c4actor.{AssembledContext, Context, GetByPK, IdGenUtil, LEvent, ListConfig, WithPK}
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.c4
import ee.cone.c4gate.AlienProtocol.{N_FromAlienWish, U_FromAlienState, U_FromAlienStatus, U_FromAlienWishes, U_ToAlienAck, U_ToAlienMessage}
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.FromAlienWishUtilImpl._
import ee.cone.c4gate.HttpProtocol.N_Header

import java.time.Instant

@c4("SessionUtilApp") final class SessionUtilImpl(
  idGenUtil: IdGenUtil, eventLogUtil: EventLogUtil,
  getAuthenticatedSession: GetByPK[U_AuthenticatedSession],
  getFromAlienStatus: GetByPK[U_FromAlienStatus],
) extends SessionUtil {
  def create(userName: String, headers: List[N_Header]): (String, LEvents) = {
    val (logKey, logEvents) = eventLogUtil.create()
    val sessionRandomKey = idGenUtil.srcIdRandom()
    val sessionKey = idGenUtil.srcIdFromStrings(sessionRandomKey)
    val until = Instant.now.plusSeconds(20).getEpochSecond
    val session = U_AuthenticatedSession(sessionKey, userName, until, headers, logKey)
    val status = U_FromAlienStatus(sessionKey, until, isOnline = true)
    (s"$sessionKey:$sessionRandomKey", LEvent.update(Seq(session, status)) ++ logEvents)
  }
  def check(sessionFullKey: String): String = sessionFullKey.split(":") match {
    case Array(sessionKey, sessionRandomKey) if sessionKey == idGenUtil.srcIdFromStrings(sessionRandomKey) => sessionKey
  }
  def purge(local: Context, sessionKey: String): LEvents = {
    def rm[T<:Product](get: GetByPK[T]): LEvents = get.ofA(local).get(sessionKey).toSeq.flatMap(LEvent.delete)
    val sessionOpt = getAuthenticatedSession.ofA(local).get(sessionKey)
    val logEvents = sessionOpt.toSeq.flatMap(s=>eventLogUtil.purgeAll(local,s.logKey))
    logEvents ++ rm(getAuthenticatedSession) ++ rm(getFromAlienStatus)
  }
  def trySetStatus(world: AssembledContext, sessionKey: String, expirationSecond: Long, isOnline: Boolean): LEvents = {
    val wasOpt = getFromAlienStatus.ofA(world).get(sessionKey)
    val willOpt = wasOpt.map(_.copy(expirationSecond = expirationSecond, isOnline = isOnline))
    if(wasOpt == willOpt) Nil else LEvent.update(willOpt.toSeq)
  }
  def expired(local: AssembledContext, sessionKey: String): Boolean =
    getFromAlienStatus.ofA(local).get(sessionKey).forall(_.expirationSecond < Instant.now.getEpochSecond)
  def logOut(local: AssembledContext, sessionKey: String): LEvents =
    LEvent.delete(getFromAlienStatus.ofA(local).get(sessionKey).toSeq)
}

@c4("SessionUtilApp") final class LocationUtilImpl(
  getFromAlienState: GetByPK[U_FromAlienState], sessionUtil: SessionUtil,
) extends LocationUtil {
  import sessionUtil.expired
  def location(local: AssembledContext, sessionKey: String): String =
    getFromAlienState.ofA(local).get(sessionKey).fold("")(_.location)
  def setLocation(sessionKey: String, value: String): LEvents =
    LEvent.update(U_FromAlienState(sessionKey, value))
  def setLocationHash(local: AssembledContext, sessionKey: String, value: String): LEvents = {
    val locationWithoutHash = location(local, sessionKey).split("#") match { case Array(l) => l case Array(l, _) => l }
    setLocation(sessionKey, s"$locationWithoutHash#$value")
  }
  def purge(local: AssembledContext, sessionKey: String): LEvents =
    getFromAlienState.ofA(local).get(sessionKey).toSeq.flatMap(LEvent.delete)
  def purgeAllExpired(world: AssembledContext): LEvents =
    LEvent.delete(getFromAlienState.ofA(world).values.filter(m => expired(world, m.sessionKey)).toSeq.sortBy(_.sessionKey))
}

@c4("SessionUtilApp") final class FromAlienWishUtilImpl(
  idGenUtil: IdGenUtil, sessionUtil: SessionUtil,
  getFromAlienWishes: GetByPK[U_FromAlienWishes], getObsForBranch: GetByPK[ObsForBranch],
  getToAlienAck: GetByPK[U_ToAlienAck],
) extends FromAlienWishUtil {
  import sessionUtil.expired
  import LEvent.{delete,update}
  def getBranchWish(world: AssembledContext, branchKey: String): Option[BranchWish] = (for {
    obsForBranch <- getObsForBranch.ofA(world).get(branchKey).toSeq
    o <- obsForBranch.values
    w <- o.wish
  } yield BranchWish(branchKey, o.sessionKey, w.index, w.value)).headOption
  def ackList(world: AssembledContext, branchKey: String): List[(String,Long)] = for {
    obsForBranch <- getObsForBranch.ofA(world).get(branchKey).toList
    o <- obsForBranch.values
  } yield (o.srcId, o.ack)
  def observerKey(branchKey: String, sessionKey: String): SrcId = idGenUtil.srcIdFromStrings(branchKey, sessionKey)
  def addAck(wish: BranchWish): LEvents =
    update(U_ToAlienAck(observerKey(wish.branchKey, wish.sessionKey), wish.branchKey, wish.sessionKey, wish.index))
  def trySetWishes(world: AssembledContext, branchKey: String, sessionKey: String, value: String): LEvents =
    if(expired(world, sessionKey)) Nil else {
      val values = parsePairs(value).map{ case (k,v) => (k.toLong,v) }.toList
      val srcId = observerKey(branchKey, sessionKey)
      val wishes = U_FromAlienWishes(srcId, branchKey, sessionKey, values.map{ case (i,v) => N_FromAlienWish(i,v)})
      if (getFromAlienWishes.ofA(world).get(wishes.srcId).contains(wishes)) Nil else update(wishes)
    }
  def parseSeq(value: String): Seq[String] = value match {
    case "" => Nil case v if v.startsWith("-") => v.substring(1).split("\n-",-1).map(_.replace("\n ","\n")).toSeq
  }
  def parsePairs(value: String): Seq[(String,String)] = parseSeq(value).grouped(2).map{ case Seq(k,v) => k->v }.toSeq
  private def serialize(vs: Seq[String]): String = vs.map(v=>s"""-${v.replace("\n","\n ")}""").mkString("\n")
  def redraw(world: AssembledContext, branchKey: String, actorKey: String): LEvents = {
    val wishes = serialize(Seq(System.currentTimeMillis.toString, serialize(Seq("x-r-op","redraw"))))
    trySetWishes(world, branchKey, actorKey, wishes)
  }
  def purgeAllExpired(world: AssembledContext): LEvents = (
    delete(getFromAlienWishes.ofA(world).values.filter(w => expired(world, w.sessionKey)).toSeq.sortBy(_.srcId)) ++
    delete(getToAlienAck.ofA(world).values.filter(ack => expired(world, ack.sessionKey)).toSeq.sortBy(_.srcId))
  )
}
object FromAlienWishUtilImpl{
  case class Obs(srcId: SrcId, sessionKey: String, wish: Option[N_FromAlienWish], ack: Long)
  case class ObsForBranch(branchKey: String, values: List[Obs])
}

@c4assemble("SessionUtilApp") class FromAlienWishAssembleBase {
  type ByBranch = SrcId
  def map(key: SrcId, wishes: Each[U_FromAlienWishes], ackList: Values[U_ToAlienAck]): Values[(ByBranch,Obs)] = {
    val ackIndex = ackList.map(_.index).maxOption.getOrElse(0L)
    val wishOpt = wishes.values.filter(_.index > ackIndex).minByOption(_.index)
    Seq(wishes.logKey -> Obs(wishes.srcId, wishes.sessionKey, wishOpt, ackIndex))
  }
  def join(key: SrcId, @by[ByBranch] obs: Values[Obs]): Values[(SrcId,ObsForBranch)] =
    Seq(WithPK(ObsForBranch(key, obs.sortBy(_.srcId).toList)))
}

@c4("SessionUtilApp") final class SessionListUtilImpl(
  getAuthenticatedSession: GetByPK[U_AuthenticatedSession],
  getFromAlienState: GetByPK[U_FromAlienState],
  getFromAlienStatus: GetByPK[U_FromAlienStatus],
) extends SessionListUtil {
  def list(world: AssembledContext): Seq[SessionListItem] = {
    val statuses = getFromAlienStatus.ofA(world)
    val states = getFromAlienState.ofA(world)
    getAuthenticatedSession.ofA(world).values.toSeq.sortBy(_.logKey).map{ session =>
      val isOnline = statuses.get(session.sessionKey).exists(_.isOnline)
      val location = states.get(session.sessionKey).fold("")(_.location)
      SessionListItem(branchKey = session.logKey, userName = session.userName, location = location, isOnline = isOnline)
    }
  }
}

@c4("SessionUtilApp") final class ToAlienMessageUtilImpl(
  idGenUtil: IdGenUtil, sessionUtil: SessionUtil,
  getToAlienMessage: GetByPK[U_ToAlienMessage], getToAlienMessageList: GetByPK[ToAlienMessageList],
) extends ToAlienMessageUtil {
  import sessionUtil.expired
  def create(sessionKey: String, value: String): LEvents =
    LEvent.update(U_ToAlienMessage(idGenUtil.srcIdRandom(), sessionKey, value))
  def delete(world: AssembledContext, messageKey: String): LEvents =
    LEvent.delete(getToAlienMessage.ofA(world).get(messageKey).toSeq)
  def list(world: AssembledContext, sessionKey: String): List[(String,String)] =
    getToAlienMessageList.ofA(world).get(sessionKey).toList.flatMap(_.messages).map(m=>(m.srcId,m.value))
  def purgeAllExpired(world: AssembledContext): LEvents =
    LEvent.delete(getToAlienMessage.ofA(world).values.filter(m => expired(world, m.sessionKey)).toSeq.sortBy(_.srcId))
}
case class ToAlienMessageList(sessionKey: String, messages: List[U_ToAlienMessage])
@c4assemble("SessionUtilApp") class ToAlienMessageAssembleBase {
  type BySession = SrcId
  def map(key: SrcId, message: Each[U_ToAlienMessage]): Values[(BySession,U_ToAlienMessage)] =
    Seq(message.sessionKey -> message)
  def join(key: SrcId, @by[BySession] messages: Values[U_ToAlienMessage]): Values[(SrcId,ToAlienMessageList)] =
    Seq(WithPK(ToAlienMessageList(key, messages.toList)))
}
