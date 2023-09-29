package ee.cone.c4actor

import ee.cone.c4actor.Types.NextOffset

trait Consuming {
  def process[R](from: NextOffset, body: Consumer=>R): R
  def process[R](from: List[(TxLogName,NextOffset)], body: Consumer=>R): R
}
trait Consumer {
  def poll(): List[RawEvent]
  def endOffset: NextOffset
  def beginningOffset: NextOffset
}

trait QPurging {
  def process[R](txLogName: TxLogName, body: QPurger=>R): R
}
trait QPurger {
  def delete(beforeOffset: NextOffset): Unit
}
