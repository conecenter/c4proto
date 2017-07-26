package ee.cone.c4actor

import java.time.Instant

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.{Map, Queue, Seq}
import Function.chain

class WorldTxImpl(
  reducer: ReducerImpl,
  val world: World,
  val toSend: Queue[Update],
  val toDebug: Queue[LEvent[Product]]
) extends WorldTx {
  private def nextWorld(nextToSend: List[Update]) =
    reducer.reduceRecover(world, nextToSend)
  def add[M<:Product](out: Seq[LEvent[M]]): WorldTx = {
    if(out.isEmpty) return this
    val nextToSend = out.map(reducer.qMessages.toUpdate).toList
    val nextToDebug = (toDebug /: out)((q,e)⇒q.enqueue(e:LEvent[Product]))
    new WorldTxImpl(reducer, nextWorld(nextToSend), toSend.enqueue(nextToSend), nextToDebug)
  }
  def add(nextToSend: List[Update]): WorldTx = {
    if(nextToSend.isEmpty) return this
    new WorldTxImpl(reducer, nextWorld(nextToSend), toSend.enqueue(nextToSend), toDebug)
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
  def reduceRecover(world: World, recs: List[Update]): World = {
    //println(s"recs ${recs.size}")
    TreeAssemblerKey.of(world)(qMessages.toTree(recs).asInstanceOf[Map[WorldKey[_],Index[Object,Object]]])(world)
  }
  /*
  def reduceRaw(world: World, data: Array[Byte], offset: Long): World =
    reduceRecover(world, qMessages.toUpdates(data) ::: qMessages.offsetUpdate(offset))
*/
  def createTx(world: World): World ⇒ World =
    TxKey.set(new WorldTxImpl(this, world, Queue.empty, Queue.empty))
}

class TxTransforms(qMessages: QMessages, reducer: Reducer, initLocals: List[InitLocal]) {
  private def createLocal() =
    ((Map():World) /: initLocals)((local,initLocal)⇒initLocal.initLocal(local))
  private def index = By.srcId(classOf[TxTransform]).of
  def get(world: World): Map[SrcId,World ⇒ World] =
    index(world).transform{ case(key,_) ⇒ handle(world,key) }
  private def handle(world: World, key: SrcId): World ⇒ World = ((local:World) ⇒
    if(local.isEmpty) createLocal() else local
  ).andThen{ local ⇒
    if(
      qMessages.worldOffset(world) < OffsetWorldKey.of(local) ||
      Instant.now.isBefore(SleepUntilKey.of(local))
    ) local else try {
      Trace{
        reducer.createTx(world)
          .andThen(chain(index(world).getOrElse(key,Nil).map(t⇒t.transform(_))))
          .andThen(qMessages.send)(local)
      }
    } catch {
      case exception: Exception ⇒
        println(s"Tx failed [$key][${Thread.currentThread.getName}][\n${exception.getStackTrace.map(l⇒s"  $l\n").mkString}]")
        // exception.printStackTrace() //??? |Nil|throw
        val was = ErrorKey.of(local)
        chain(List(
          ErrorKey.set(exception :: was),
          SleepUntilKey.set(Instant.now.plusSeconds(was.size))
        ))(createLocal())
      /*case e: Throwable ⇒
        println("Throwable0")
        e.printStackTrace()
        throw e*/
    }
  }
}

class SerialObserver(localStates: Map[SrcId,Map[WorldKey[_],Object]])(
  transforms: TxTransforms
) extends Observer {
  def activate(world: World): Seq[Observer] = {
    val nLocalStates = transforms.get(world).transform{ case(key,handle) ⇒
      handle(localStates.getOrElse(key,Map.empty))
    }
    List(new SerialObserver(nLocalStates)(transforms))
  }
}

class ParallelObserver(
  localStates: Map[SrcId,FatalFuture[World]],
  transforms: TxTransforms,
  execution: Execution
) extends Observer {
  private def empty: FatalFuture[World] = execution.future(Map.empty)
  def activate(world: World): Seq[Observer] = {
    val inProgressMap = localStates.filterNot{ case(k,v) ⇒ v.isCompleted }
    val toAdd = transforms.get(world).transform{ case(key,handle) ⇒
      localStates.getOrElse(key,empty).map(handle)
    }
    val nLocalStates = inProgressMap ++ toAdd
    List(new ParallelObserver(nLocalStates,transforms,execution))
  }
}

object ProtocolDataDependencies {
  def apply(protocols: List[Protocol]): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters.filter(_.hasId)).map{ adapter ⇒
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