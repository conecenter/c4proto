
package ee.cone.c4gate

import ee.cone.c4assemble.Types.World

trait SenderToAgent {
  def add(data: Array[Byte]): Unit
  def close()
}

trait WorldProvider {
  def createTx(): World
}