package ee.cone.c4actor

import ee.cone.c4actor.Types.World

import scala.collection.immutable.Queue

class MessageMappingImpl(reducer: ReducerImpl, val actorName: ActorName, val world: World, val toSend: Queue[QRecord]) extends MessageMapping {
  def add[M<:Product](out: LEvent[M]*): MessageMapping = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toRecord).toList
    //??? insert here: application groups,  case object InstantTopicName extends TopicName
    val stateTopicName = StateTopicName(actorName)
    val nextWorld = reducer.reduceRecover(world, nextToSend.filter(_.topic==stateTopicName))
    new MessageMappingImpl(reducer, actorName, nextWorld, toSend.enqueue(nextToSend))
  }
}

class ReducerImpl(
  val qMessages: QMessages, treeAssembler: TreeAssembler
) extends Reducer {
  def reduceRecover(world: World, recs: List[QRecord]): World = {
    val diff = qMessages.toTree(recs)
    treeAssembler.replace(world, diff)
  }
  def createMessageMapping(actorName: ActorName, world: World): MessageMapping =
    new MessageMappingImpl(this, actorName, world, Queue.empty)
}
