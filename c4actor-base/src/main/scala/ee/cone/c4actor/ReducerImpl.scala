package ee.cone.c4actor

import ee.cone.c4actor.Types.World

import scala.collection.immutable.Queue

class MessageMappingImpl(reducer: ReducerImpl, val topicName: TopicName, val world: World, val toSend: Queue[QRecord]) extends MessageMapping {
  def add[M<:Product](out: LEvent[M]*): MessageMapping = {
    val nextToSend = out.map(reducer.qMessages.toRecord).toList
    //??? insert here: application groups,  case object InstantTopicName extends TopicName
    val nextWorld = reducer.reduceRecover(world, nextToSend.filter(_.topic==topicName))
    new MessageMappingImpl(reducer, topicName, nextWorld, toSend.enqueue(nextToSend))
  }
}

class ReducerImpl(
  val qMessages: QMessages, treeAssembler: TreeAssembler
) extends Reducer {
  def reduceRecover(world: World, recs: List[QRecord]): World = {
    val diff = qMessages.toTree(recs)
    treeAssembler.replace(world, diff)
  }
  def createMessageMapping(topicName: TopicName, world: World): MessageMapping =
    new MessageMappingImpl(this, topicName, world, Queue.empty)
}

class SerialWorldObserver extends WorldObserver {
  def activate(getWorld: () ⇒ World): Seq[WorldObserver] = {
    val world = getWorld()
    ???
  }
}


/*

Task[T]
  offset: Long
  value: Product

MultiUpdate
  updates: List[Update]

Update
    key: TopicKey
    fromVal: bs
    toVal: bs


          val qMessageMapper = qMessageMapperFactory.create(messageMappers)
qMessageMapper: QMessageMapper, rawQSender: KafkaRawQSender,
            val mapping = reducer.createMessageMapping(topicName, localWorldRef.get)
            val res = (mapping /: recs)(qMessageMapper.mapMessage)
            val metadata = res.toSend.map(rawQSender.sendStart)
            metadata.foreach(_.get())
            */