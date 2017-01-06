package ee.cone.c4actor

import scala.collection.immutable.Queue
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey

trait Reducer {
  def createWorld: World ⇒ World
  def reduceRecover(world: World, recs: List[QRecord]): World
  def reduceReceive(actorName: ActorName, world: World, inboxRecs: Seq[QRecord]): (World, Queue[QRecord])
  def createTx(world: World): World ⇒ World
}

case object ErrorKey extends WorldKey[Option[Exception]](None)

trait InitLocal {
  def initLocal: World ⇒ World
}