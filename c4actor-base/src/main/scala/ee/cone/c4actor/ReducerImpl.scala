package ee.cone.c4actor

import java.util.concurrent.CompletableFuture

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.{Map, Queue}
import scala.concurrent.Future

class WorldTxImpl(reducer: ReducerImpl, val world: World, val toSend: Queue[Update]) extends WorldTx {
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx = {
    if(out.isEmpty) return this
    //println(s"add ${out.toList}")
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
  def reduceRecover(world: World, recs: List[QRecord]): World = {
    //println(s"recs ${recs.size}")
    TreeAssemblerKey.of(world)(qMessages.toTree(recs).asInstanceOf[Map[WorldKey[_],Index[Object,Object]]])(world)
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
  private def createLocal(): World =
    ((Map():World) /: initLocals)((local,initLocal)⇒initLocal.initLocal(local))
  def activate(getWorld: () ⇒ World): Seq[Observer] = {
    val world = getWorld()
    val transforms: Index[SrcId, TxTransform] = By.srcId(classOf[TxTransform]).of(world)
    //println(WorldStats.make(world))
    val nLocalStates = transforms.map{ case (key, transformList) ⇒
      key → localStates.get(key).orElse(Option(createLocal())).map{ local ⇒
        //println(s"${qMessages.worldOffset(world)} < ${OffsetWorldKey.of(local)} ==> ${qMessages.worldOffset(world) < OffsetWorldKey.of(local)}")
        if(qMessages.worldOffset(world) < OffsetWorldKey.of(local)) local else try {
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

/*
* in trans? in was? !isDone?
*  0 0 x => -
*  0 1 0 => del
*  0 1 1 => keep same
*  1 0 x => new
*  1 1 x => chain
* */

class ParallelObserver(localStates: Map[SrcId,Future[World]])(
    qMessages: QMessages, reducer: Reducer, initLocals: List[InitLocal]
) extends Observer {
  private def createLocal() =
    ((Map():World) /: initLocals)((local,initLocal)⇒initLocal.initLocal(local))
  private def handle: (() ⇒ World, SrcId) ⇒ World ⇒ World = ???

  def activate(getWorld: () ⇒ World): Seq[Observer] = {
    val transformKeys: Set[SrcId] = By.srcId(classOf[TxTransform]).of(getWorld()).keySet
    val nLocalStates = (transformKeys ++ localStates.keySet).flatMap{ srcId ⇒
      if(transformKeys(srcId)) Option(srcId →
        localStates.getOrElse(srcId,Future(createLocal()))
        .map(handle(getWorld,srcId))
        .recover{
          case e: Exception ⇒
            e.printStackTrace() //??? |Nil|throw
            ErrorKey.set(Some(e))(createLocal())
        }
      )
      else localStates.get(srcId).filterNot(_.isCompleted).map(srcId→_)
    }.toMap
    Seq(new ParallelObserver(nLocalStates)(qMessages,reducer,initLocals))
  }
}

case class SimpleTxTransform[P<:Product](srcId: SrcId, todo: List[LEvent[P]]) extends TxTransform {
  def transform(local: World): World = LEvent.add(todo)(local)
}

object ProtocolDataDependencies {
  def apply(protocols: List[Protocol]): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters).map{ adapter ⇒
      new OriginalWorldPart(By.srcId(adapter.className))
    }
}
