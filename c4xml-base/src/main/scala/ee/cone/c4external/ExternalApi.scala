package ee.cone.c4external

trait ExtDBSync {
  def sync: List[ExtUpdatesWithTxId] ⇒ List[(String, Int)]
}
