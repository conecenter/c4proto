package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.{Map, Queue}

class WorldTxImpl(reducer: ReducerImpl, val world: World, val toSend: Queue[Update]) extends WorldTx {
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toUpdate).toList
    val nextWorld = reducer.reduceRecover(world, nextToSend.map(reducer.qMessages.toRecord(NoTopicName,_)))
    new WorldTxImpl(reducer, nextWorld, toSend.enqueue(nextToSend))
  }
}

case object TreeAssemblerKey extends WorldKey[Replace](_⇒throw new Exception)

class ReducerImpl(
  val qMessages: QMessages,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]]
) extends Reducer {
  def createWorld: World ⇒ World =
    TreeAssemblerKey.set(treeAssembler.replace(getDependencies()))
  def reduceRecover(world: World, recs: List[QRecord]): World =
    TreeAssemblerKey.of(world)(qMessages.toTree(recs))(world)
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
    TxKey.set(new WorldTxImpl(this, world, Queue.empty))
}

object WorldStats {
  def make(world: World): String = world.collect{ case (worldKey, index:Map[_,_]) ⇒
    val sz = index.values.collect { case s: Seq[_] ⇒ s.size }.sum
    s"$worldKey : ${index.size} : $sz"
  }.mkString("\n")
}

class SerialObserver(localStates: Map[SrcId,Map[WorldKey[_],Object]])(
    qMessages: QMessages, reducer: Reducer, initLocals: List[InitLocal]
) extends Observer {
  private def createLocal() =
    ((Map():World) /: initLocals)((local,initLocal)⇒initLocal.initLocal(local))
  def activate(getWorld: () ⇒ World): Seq[Observer] = {
    val world = getWorld()
    val transforms: Index[SrcId, TxTransform] = By.srcId(classOf[TxTransform]).of(world)
    //println(WorldStats.make(world))
    val nLocalStates = transforms.map{ case (key, transformList) ⇒
      key → localStates.get(key).orElse(Option(createLocal())).map{ local ⇒
        if(OffsetWorldKey.of(world) < OffsetWorldKey.of(local)) local else try {
            reducer.createTx(world)
            .andThen(local ⇒ (local /: transformList) ((local, transform) ⇒ transform.transform(local)))
            .andThen(qMessages.send)(local)
        } catch {
          case e: Exception ⇒
            e.printStackTrace() //??? |Nil|throw
            ErrorKey.set(Some(e))(createLocal())
        }
      }.get
    }
    Seq(new SerialObserver(nLocalStates)(qMessages,reducer,initLocals))
  }
}

case class SimpleTxTransform[P<:Product](todo: List[LEvent[P]]) extends TxTransform {
  def transform(local: World): World = LEvent.add(todo)(local)
}

object ProtocolDataDependencies {
  def apply(protocols: List[Protocol]): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters).map{ adapter ⇒
      new OriginalWorldPart(By.srcId(adapter.className))
    }
}
