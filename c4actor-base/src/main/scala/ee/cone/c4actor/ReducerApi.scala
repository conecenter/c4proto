package ee.cone.c4actor

import java.time.Instant

import scala.collection.immutable.{Queue, Seq}
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

trait Reducer {
  def createWorld: World ⇒ World
  def reduceRecover(world: World, recs: List[QRecord]): World
  def reduceReceive(actorName: ActorName, world: World, inboxRecs: Seq[QRecord]): (World, Queue[QRecord])
  def createTx(world: World): World ⇒ World
}

case object ErrorKey extends WorldKey[List[Exception]](Nil)
case object SleepUntilKey extends WorldKey[Instant](Instant.MIN)

trait InitLocal {
  def initLocal: World ⇒ World
}