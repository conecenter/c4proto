package ee.cone.c4actor

import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.{Map, Queue}

trait Reducer {
  def createWorld: World ⇒ World
  def reduceRecover(world: World, recs: List[QRecord]): World
  def reduceReceive(actorName: ActorName, world: World, inboxRecs: Seq[QRecord]): (World, Queue[QRecord])
  def createTx(world: World): World ⇒ World
}
