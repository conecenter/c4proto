package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.World

import scala.collection.immutable.Queue

class WorldTxImpl(reducer: ReducerImpl, val world: World, val toSend: Queue[Update]) extends WorldTx {
  def add[M<:Product](out: LEvent[M]*): WorldTx = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toUpdate).toList
    //??? insert here: application groups,  case object InstantTopicName extends TopicName
    val nextWorld = reducer.reduceRecover(world, nextToSend.map(reducer.qMessages.toRecord(NoTopicName,_)))
    new WorldTxImpl(reducer, nextWorld, toSend.enqueue(nextToSend))
  }
}

class ReducerImpl(
  val qMessages: QMessages, treeAssembler: TreeAssembler
) extends Reducer {
  def reduceRecover(world: World, recs: List[QRecord]): World = {
    val diff = qMessages.toTree(recs)
    treeAssembler.replace(world, diff)
  }
  def reduceReceive(actorName: ActorName, world: World, inboxRecs: Seq[QRecord]): (World, Queue[QRecord]) =
    ((world,Queue.empty[QRecord]) /: inboxRecs){ (s,inboxRec) ⇒
      val(prevWorld,prevQueue) = s
      try {
        val stateRecs = qMessages.toRecords(actorName, inboxRec)
        (reduceRecover(prevWorld,stateRecs), prevQueue.enqueue(stateRecs))
      } catch {
        case e: Exception ⇒
          e.printStackTrace()
          (prevWorld,prevQueue) // ??? exception to record
      }
    }
  def createTx(world: World): WorldTx =
    new WorldTxImpl(this, world, Queue.empty)
}
