package ee.cone.c4external

import ee.cone.c4actor.QProtocol.Update

trait ExtDBSync {
  def externals: Map[String, Long]
  def upload: List[ExtUpdatesWithTxId] ⇒ List[(String, Int)]
  def download: List[ByPKExtRequest] ⇒ List[Update]
}
