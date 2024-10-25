package ee.cone.c4gate

import ee.cone.c4actor.Context
import ee.cone.c4actor.Types.LEvents
import ee.cone.c4gate.HttpProtocol.N_Header


trait SessionUtil {
  def create(userName: String, headers: List[N_Header]): (String, LEvents)
  def purge(local: Context, sessionKey: String): LEvents
  def location(local: Context, sessionKey: String): String
  def setLocation(local: Context, sessionKey: String, value: String): LEvents
  def setStatus(local: Context, sessionKey: String, timeoutSec: Long, isOnline: Boolean): LEvents
  def ackList(local: Context, sessionKey: String): List[N_Header]
  def addAck(local: Context, sessionKey: String, clientKey: String, index: String): LEvents
  def expired(local: Context, sessionKey: String): Boolean
}
