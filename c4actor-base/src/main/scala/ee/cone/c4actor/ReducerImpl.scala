package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{Index, SrcId, World}

import scala.collection.immutable.Queue

class WorldTxImpl(reducer: ReducerImpl, world: World, val toSend: Queue[Update]) extends WorldTx {
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toUpdate).toList
    val nextWorld = reducer.reduceRecover(world, nextToSend.map(reducer.qMessages.toRecord(NoTopicName,_)))
    new WorldTxImpl(reducer, nextWorld, toSend.enqueue(nextToSend))
  }
  def get[Item](cl: Class[Item]): Index[SrcId,Item] = By.srcId(cl).of(world)
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
  def createTx(world: World): WorldTx =
    new WorldTxImpl(this, world, Queue.empty)
}

class SerialObserver(needWorldOffset: Long)(qMessages: QMessages, reducer: Reducer, transform: TxTransform) extends Observer {
  def activate(getWorld: () ⇒ World): Seq[Observer] = try {
    val world = getWorld()
    val tx = reducer.createTx(world)
    if(OffsetWorldKey.of(world) < needWorldOffset) return Seq(this)
    val nTx = transform.transform(tx)
    //println(s"ntx:${nTx.toSend.size}")
    val offset = qMessages.send(nTx)
    Seq(offset.map(o⇒new SerialObserver(o+1)(qMessages,transform)).getOrElse(this))
  } catch {
    case e: Exception ⇒
      e.printStackTrace() //??? |Nil|throw
      Seq(this)
  }
}
