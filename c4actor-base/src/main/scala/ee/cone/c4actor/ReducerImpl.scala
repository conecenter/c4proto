package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.{Map, Queue}

class WorldTxImpl(reducer: ReducerImpl, val world: World, val toSend: Queue[Update]) extends WorldTx {
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toUpdate).toList
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
        //println(stateRecs.size)
        (reduceRecover(prevWorld,stateRecs), prevQueue.enqueue(stateRecs))
      } catch {
        case e: Exception ⇒
          e.printStackTrace()
          (prevWorld,prevQueue) // ??? exception to record
      }
    }
  def createTx(world: World): World ⇒ World =
    TxKey.transform(_⇒new WorldTxImpl(this, world, Queue.empty))
}

class SerialObserver(localStates: Map[SrcId,Map[WorldKey[_],Object]])(qMessages: QMessages, reducer: Reducer) extends Observer {
  def activate(getWorld: () ⇒ World): Seq[Observer] = try {
    val world = getWorld()
    val transforms: Index[SrcId, TxTransform] = By.srcId(classOf[TxTransform]).of(world)
    val nLocalStates = transforms.map{ case (key, transformList) ⇒
      key → localStates.get(key).orElse(Option(Map():World)).map{ local ⇒
        if(OffsetWorldKey.of(world) < OffsetWorldKey.of(local)) local else Option(local)
          .map(reducer.createTx(world))
          .map(local ⇒ (local /: transformList)((local,transform)⇒transform.transform(local)))
          .map(qMessages.send).get
      }.get
    }
    Seq(new SerialObserver(nLocalStates)(qMessages,reducer))
  } catch {
    case e: Exception ⇒
      e.printStackTrace() //??? |Nil|throw
      Seq(this)
  }
}

case class SimpleTxTransform(key: String, todo: List[LEvent[Product]]) extends TxTransform {
  def transform(local: World): World = LEvent.add(todo:Iterable[LEvent[Product]])(local)
}