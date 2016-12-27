package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.{Map, Queue}

class WorldTxImpl(reducer: ReducerImpl, world: World, val toSend: Queue[Update], val local: World) extends WorldTx {
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toUpdate).toList
    val nextWorld = reducer.reduceRecover(world, nextToSend.map(reducer.qMessages.toRecord(NoTopicName,_)))
    new WorldTxImpl(reducer, nextWorld, toSend.enqueue(nextToSend), local)
  }
  def get[Item](cl: Class[Item]): Index[SrcId,Item] = By.srcId(cl).of(world)
  def setLocal[Item<:Object](key: WorldKey[Item], value: Item): WorldTx =
    new WorldTxImpl(reducer, world, toSend, local + (key→value))
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
  def createTx(world: World, local: World): WorldTx =
    new WorldTxImpl(this, world, Queue.empty, local)
}

class SerialObserver(localStates: Map[SrcId,Map[WorldKey[_],Object]])(qMessages: QMessages, reducer: Reducer) extends Observer {
  def activate(getWorld: () ⇒ World): Seq[Observer] = try {
    val world = getWorld()
    val transforms: Index[SrcId, TxTransform] = By.srcId(classOf[TxTransform]).of(world)
    val nLocalStates = transforms.map{ case (key, transformList) ⇒
      val localState = localStates.getOrElse(key,Map())
      val nLocal = if(OffsetWorldKey.of(world) < OffsetWorldKey.of(localState)) localState else {
        val tx = reducer.createTx(world, localState)
        val nTx = (tx /: transformList)((tx,transform)⇒transform.transform(tx))
        qMessages.send(nTx).map(o⇒ tx.setLocal(OffsetWorldKey, o+1)).getOrElse(nTx).local
      }
      key → nLocal
    }
    Seq(new SerialObserver(nLocalStates)(qMessages,reducer))
  } catch {
    case e: Exception ⇒
      e.printStackTrace() //??? |Nil|throw
      Seq(this)
  }
}

case class SimpleTxTransform(key: String, todo: List[LEvent[_]]) extends TxTransform {
  def transform(tx: WorldTx): WorldTx = tx.add(todo)
}