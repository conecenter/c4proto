package ee.cone.c4gate

import ee.cone.c4actor.{AssembledContext, Context, GetByPK, IdGenUtil, LEvent, ListConfig, WithPK}
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4assemble.Types.{Each, Outs, Values}
import ee.cone.c4assemble.{OutFactory, Single, by, c4assemble}
import ee.cone.c4di.c4
import ee.cone.c4gate.AlienProtocol.{N_FromAlienWish, U_FromAlienState, U_FromAlienStatus, U_FromAlienWishes, U_ToAlienAck}
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.FromAlienWishUtilImpl._
import ee.cone.c4gate.HttpProtocol.N_Header

import java.time.Instant

@c4("SessionUtilApp") final class SessionUtilImpl(
  idGenUtil: IdGenUtil, eventLogUtil: EventLogUtil,
  getAuthenticatedSession: GetByPK[U_AuthenticatedSession],
  getFromAlienState: GetByPK[U_FromAlienState],
  getFromAlienStatus: GetByPK[U_FromAlienStatus],
) extends SessionUtil {
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
  def setLocationHash(local: Context, sessionKey: String, value: String): LEvents = {
    val locationWithoutHash = location(local, sessionKey).split("#") match { case Array(l) => l case Array(l, _) => l }
    setLocation(local, sessionKey, s"$locationWithoutHash#$value")
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
