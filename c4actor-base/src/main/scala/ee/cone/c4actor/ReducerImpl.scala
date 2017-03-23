package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.{Map, Queue}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global // requires usage of `blocking{}`

import Function.chain

class WorldTxImpl(
  reducer: ReducerImpl,
  val world: World,
  val toSend: Queue[Update],
  val toDebug: Queue[LEvent[Product]]
) extends WorldTx {
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toUpdate).toList
    val nextWorld = reducer.reduceRecover(world, nextToSend.map(reducer.qMessages.toRecord(NoTopicName,_)))
    val nextToDebug = toDebug.enqueue(out).asInstanceOf[Queue[LEvent[Product]]]
    new WorldTxImpl(reducer, nextWorld, toSend.enqueue(nextToSend), nextToDebug)
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
    TxKey.set(new WorldTxImpl(this, world, Queue.empty, Queue.empty))
}

object WorldStats {
  def make(world: World): String = world.collect{ case (worldKey, index:Map[_,_]) ⇒
    val sz = index.values.collect { case s: Seq[_] ⇒ s.size }.sum
    s"$worldKey : ${index.size} : $sz"
  }.mkString("\n")
}

class TxTransforms(qMessages: QMessages, reducer: Reducer, initLocals: List[InitLocal]) {
  private def createLocal() =
    ((Map():World) /: initLocals)((local,initLocal)⇒initLocal.initLocal(local))
  private def index = By.srcId(classOf[TxTransform]).of
  def get(getWorld: () ⇒ World): Map[SrcId,World ⇒ World] =
    index(getWorld()).transform{ case(key,_) ⇒ handle(getWorld,key) }
  private def handle(getWorld: () ⇒ World, key: SrcId): World ⇒ World = ((local:World) ⇒
    if(local.isEmpty) createLocal() else local
    ).andThen{ local ⇒
    val world = getWorld()
    if(qMessages.worldOffset(world) < OffsetWorldKey.of(local)) local else try {
      reducer.createTx(world)
        .andThen(chain(index(world).getOrElse(key,Nil).map(t⇒t.transform(_))))
        .andThen(qMessages.send)(local)
    } catch {
      case e: Exception ⇒
        e.printStackTrace() //??? |Nil|throw
        ErrorKey.set(Some(e))(createLocal())
      case e: Throwable ⇒
        e.printStackTrace()
        throw e
    }
  }
}

class SerialObserver(localStates: Map[SrcId,Map[WorldKey[_],Object]])(
  transforms: TxTransforms
) extends Observer {
  def activate(ctx: ObserverContext): Seq[Observer] = {
    val nLocalStates = transforms.get(ctx.getWorld).transform{ case(key,handle) ⇒
      handle(localStates.getOrElse(key,Map.empty))
    }
    Seq(new SerialObserver(nLocalStates)(transforms))
  }
}

class ParallelObserver(localStates: Map[SrcId,List[Future[World]]])(
  transforms: TxTransforms
) extends Observer {
  private def empty: List[Future[World]] = List(Future.successful(Map.empty))

  def activate(ctx: ObserverContext): Seq[Observer] = {
    val inProgressMap = localStates
      .transform{ case(k,futures) ⇒ futures.filter(!_.isCompleted) }
      .filter{ case(k,v) ⇒ v.nonEmpty }
    val inProgress: SrcId ⇒ List[Future[World]] = inProgressMap.getOrElse(_,Nil)
    val toAdd: Map[SrcId, List[Future[World]]] = transforms.get(ctx.getWorld)
      .filterKeys(inProgress(_).size <= 1)
      .transform{ case(key,handle) ⇒
        localStates.getOrElse(key,empty).head.map(handle) :: inProgress(key)
      }
    val nLocalStates = inProgressMap ++ toAdd
    Seq(new ParallelObserver(nLocalStates)(transforms))
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

/*
* in trans? in was? !isDone?
*  0 0 x => -
*  0 1 0 => del
*  0 1 1 => keep same
*  1 0 x => new
*  1 1 x => chain
* */