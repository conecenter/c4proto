package ee.cone.c4actor

import ee.cone.c4actor.Types.NextOffset

trait Consuming {
  def process[R](from: NextOffset, body: Consumer⇒R): R
}
trait Consumer {
  def poll(): List[RawEvent]
  def endOffset: NextOffset
}