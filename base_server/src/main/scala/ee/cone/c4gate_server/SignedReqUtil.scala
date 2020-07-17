package ee.cone.c4gate_server

import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol._

trait SignedReqUtil {
  def catchNonFatal: CatchNonFatal
  def signed(headers: List[N_Header]): Option[String]
  def respond(succeeded: List[(S_HttpRequest, List[N_Header])], failed: List[(S_HttpRequest, String)]): List[LEvent[Product]]
}
