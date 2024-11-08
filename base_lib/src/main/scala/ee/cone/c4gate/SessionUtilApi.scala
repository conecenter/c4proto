package ee.cone.c4gate

import ee.cone.c4actor.{AssembledContext, Context}
import ee.cone.c4actor.Types.LEvents
import ee.cone.c4gate.AlienProtocol.{N_FromAlienWish, U_FromAlienWishes}
import ee.cone.c4gate.HttpProtocol.N_Header


trait SessionUtil {
  def create(userName: String, headers: List[N_Header]): (String, LEvents)
  def purge(local: Context, sessionKey: String): LEvents
  def location(local: Context, sessionKey: String): String
  def setLocation(local: Context, sessionKey: String, value: String): LEvents
  def trySetStatus(local: AssembledContext, sessionKey: String, isOnline: Boolean): LEvents
  def expired(local: Context, sessionKey: String): Boolean
}

trait FromAlienWishUtil {
  def ack(local: Context, branchKey: String, sessionKey: String): Long
  def addAck(branchKey: String, sessionKey: String, index: Long): LEvents
  def addWishes(world: AssembledContext, branchKey: String, sessionKey: String, values: List[N_FromAlienWish]): LEvents
}