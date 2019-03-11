package ee.cone.c4external

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4external.ExternalProtocol.ExternalUpdates

trait ExtDBSync {
  def externals: Map[String, Long]
  def upload: List[ExternalUpdates] ⇒ List[(String, Int)]
  def download: List[ByPKExtRequest] ⇒ List[Update]
}
