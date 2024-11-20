package ee.cone.c4gate

import ee.cone.c4actor.{AssembledContext, Context}
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4gate.HttpProtocol.N_Header

trait SessionUtil {
  def create(userName: String, headers: List[N_Header]): (String, LEvents)
  def purge(local: Context, sessionKey: String): LEvents
  def location(local: Context, sessionKey: String): String
  def setLocation(local: Context, sessionKey: String, value: String): LEvents
  def trySetStatus(world: AssembledContext, sessionKey: String, expirationSecond: Long, isOnline: Boolean): LEvents
  def expired(local: AssembledContext, sessionKey: String): Boolean
}

case class BranchWish(branchKey: String, sessionKey: String, index: Long, value: String)
trait FromAlienWishUtil {
  def getBranchWish(local: AssembledContext, branchKey: String): Option[BranchWish]
  def observerKey(branchKey: String, sessionKey: String): SrcId
  def ackList(world: AssembledContext, branchKey: String): List[(String,Long)]
  def addAck(wish: BranchWish): LEvents
  def trySetWishes(world: AssembledContext, branchKey: String, sessionKey: String, value: String): LEvents
  def parseSeq(value: String): Seq[String]
  def parsePairs(value: String): Seq[(String,String)]
  def redraw(world: AssembledContext, branchKey: String, actorKey: String): LEvents
  def purgeAllExpired(world: AssembledContext): LEvents
}

case class SessionListItem(branchKey: String, userName: String, location: String, isOnline: Boolean)
trait SessionListUtil {
  def list(world: AssembledContext): Seq[SessionListItem]
}
