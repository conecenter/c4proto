package ee.cone.c4actor

import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.Map

case object ErrorsKey extends WorldKey[Index[SrcId,String]](Map.empty)

trait Reducer {
  def reduceRecover(world: World, recs: List[QRecord]): World
  def createMessageMapping(topicName: TopicName, world: World): MessageMapping
}

trait WorldObserver {
  def activate(getWorld: ()⇒World): Seq[WorldObserver]
}

trait WorldObserverApp {
  def initialWorldObservers: List[WorldObserver] = Nil
}

