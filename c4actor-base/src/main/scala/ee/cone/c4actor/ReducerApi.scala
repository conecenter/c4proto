package ee.cone.c4actor

import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.{Map, Queue}

case object ErrorsKey extends WorldKey[Index[SrcId,String]](Map.empty)

trait Reducer {
  def reduceRecover(world: World, recs: List[QRecord]): World
  def reduceReceive(actorName: ActorName, world: World, inboxRecs: Seq[QRecord]): (World, Queue[QRecord])
  def createTx(world: World): WorldTx
}

trait WorldProvider {
  def world: World
}