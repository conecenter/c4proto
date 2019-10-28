package ee.cone.c4gate

import ee.cone.c4actor.{CatchNonFatal, Context}
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpRequest}

trait SignedReqUtil {
  def catchNonFatal: CatchNonFatal
  def signed(headers: List[N_Header]): Option[String]
  def respond(succeeded: List[(S_HttpRequest, List[N_Header])], failed: List[(S_HttpRequest, String)]): Context=>Context
}
