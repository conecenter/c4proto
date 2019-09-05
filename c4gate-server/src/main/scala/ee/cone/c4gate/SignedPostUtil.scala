package ee.cone.c4gate

import ee.cone.c4actor.{CatchNonFatal, Context}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPost}

trait SignedPostUtil {
  def catchNonFatal: CatchNonFatal
  def signed(headers: List[N_Header]): Option[String]
  def respond(succeeded: List[(S_HttpPost, List[N_Header])], failed: List[(S_HttpPost, String)]): Context⇒Context
}
