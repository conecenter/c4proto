package ee.cone.c4actor

import java.time.Instant

import ee.cone.c4actor.QProtocol.Update

import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

trait Reducer {
  def createWorld: World ⇒ World
  //def reduceRaw(world: World, data: Array[Byte], offset: Long): World
  def reduceRecover(world: World, recs: List[Update]): World
  def createTx(world: World): World ⇒ World
}

case object ErrorKey extends WorldKey[List[Exception]](Nil)
case object SleepUntilKey extends WorldKey[Instant](Instant.MIN)

trait InitLocal {
  def initLocal: World ⇒ World
}