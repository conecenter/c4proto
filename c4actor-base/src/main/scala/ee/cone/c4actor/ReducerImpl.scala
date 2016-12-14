package ee.cone.c4actor

import ee.cone.c4actor.Types.World

class ReducerImpl(actorName: ActorName)(
  qMessages: QMessages, qMessageMapper: QMessageMapper,
  treeAssembler: TreeAssembler
) extends Reducer {
  def reduceRecover(world: World, recs: List[QRecord]): World = {
    val diff = qMessages.toTree(recs)
    treeAssembler.replace(world, diff)
  }
  //def reduceCheck(state: (World,List[QRecord]), rec: QRecord)
  def reduceCheck(prevWorld: World, rec: QRecord): (World, List[QRecord])= try {
    val stateTopicName = StateTopicName(actorName)
    val toSend = qMessageMapper.mapMessage(prevWorld, rec).toList
    val world = reduceRecover(prevWorld, toSend.filter(_.topic==stateTopicName))
    val errors = ErrorsKey.of(world)
    if(errors.nonEmpty) throw new Exception(errors.toString)
    (world, toSend)
  } catch {
    case e: Exception ⇒
      // ??? exception to record
      (prevWorld, Nil:List[QRecord])
  }
}

class ReducerFactoryImpl(
  qMessageMapperFactory: QMessageMapperFactory, qMessages: QMessages,
  treeAssembler: TreeAssembler
) extends ActorFactory[Reducer] {
  def create(actorName: ActorName, messageMappers: List[MessageMapper[_]]): Reducer = {
    val qMessageMapper = qMessageMapperFactory.create(actorName, messageMappers)
    new ReducerImpl(actorName)(qMessages, qMessageMapper, treeAssembler)
  }
}
