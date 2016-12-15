package ee.cone.c4actor

import ee.cone.c4actor.Types.World

class MessageMappingImpl(reducer: ReducerImpl, actorName: ActorName, val world: World, val toSend: List[QRecord]) extends MessageMapping {
  def add(out: MessageMapResult*): MessageMapping = {
    val nextToSend = out.map(reducer.qMessages.toRecord(Option(actorName),_)).toList
    val stateTopicName = StateTopicName(actorName)
    val nextWorld = reducer.reduceRecover(world, nextToSend.filter(_.topic==stateTopicName))
    new MessageMappingImpl(reducer, actorName, nextWorld, nextToSend.reverse ::: toSend)
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
    new MessageMappingImpl(this, actorName, world, Nil)
}
