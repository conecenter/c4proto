package ee.cone.c4actor

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{SrcId, TransientMap}

import scala.collection.immutable.{Map, Seq}

trait TxTransforms {
  type TransientTransform = TransientMap ⇒ TransientMap
  def get(global: Context): Map[SrcId,TransientTransform]
}

@c4component case class TxTransformsImpl(qMessages: QMessages) extends TxTransforms with LazyLogging {
  def get(global: Context): Map[SrcId,TransientTransform] =
    ByPK(classOf[TxTransform]).of(global).transform{ case(key,_) ⇒ handle(global,key) }
  private def handle(global: Context, key: SrcId): TransientTransform = ((prev:TransientMap) ⇒
    new Context(global.injected, global.assembled, prev)
  ).andThen{ (local:Context) ⇒
    val txTransform = ByPK(classOf[TxTransform]).of(local).get(key)
    if(
      txTransform.isEmpty ||
        OffsetWorldKey.of(global) < OffsetWorldKey.of(local) ||
      Instant.now.isBefore(SleepUntilKey.of(local))
    ) local.transient else try {
      Trace{
        (txTransform.get.transform(_)).andThen(qMessages.send)(local).transient
      }
    } catch {
      case exception: Exception ⇒
        logger.error(s"Tx failed [$key][${Thread.currentThread.getName}]",exception)
        // exception.printStackTrace() //??? |Nil|throw
        val was = ErrorKey.of(local)
        Function.chain(List(
          ErrorKey.set(exception :: was),
          SleepUntilKey.set(Instant.now.plusSeconds(was.size))
        ))(global).transient
      /*case e: Throwable ⇒
        println("Throwable0")
        e.printStackTrace()
        throw e*/
    }
  }
}

class SerialObserver(localStates: Map[SrcId,TransientMap])(
  transforms: TxTransforms
) extends Observer {
  def activate(global: Context): Seq[Observer] = {
    val nLocalStates = transforms.get(global).transform{ case(key,handle) ⇒
      handle(localStates.getOrElse(key,Map.empty))
    }
    List(new SerialObserver(nLocalStates)(transforms))
  }
}

class ParallelObserver(
  localStates: Map[SrcId,FatalFuture[TransientMap]],
  transforms: TxTransforms,
  execution: Execution
) extends Observer {
  private def empty: FatalFuture[TransientMap] = execution.future(Map.empty)
  def activate(global: Context): Seq[Observer] = {
    val inProgressMap = localStates.filterNot{ case(k,v) ⇒ v.isCompleted }
    val toAdd = transforms.get(global).transform{ case(key,handle) ⇒
      localStates.getOrElse(key,empty).map(handle)
    }
    val nLocalStates = inProgressMap ++ toAdd
    List(new ParallelObserver(nLocalStates,transforms,execution))
  }
}

@c4component @listed case class SerialObserverProvider(
  txTransforms: TxTransforms
)(
  observer: Observer = new SerialObserver(Map.empty)(txTransforms)
) extends InitialObserversProvider {
  def initialObservers: List[Observer] = List(observer)
}

@c4component @listed case class ParallelObserverProvider(
  txTransforms: TxTransforms, execution: Execution
)(
  observer: Observer = new ParallelObserver(Map.empty,txTransforms,execution)
) extends InitialObserversProvider {
  def initialObservers: List[Observer] = List(observer)
}


/*
* in trans? in was? !isDone?
*  0 0 x => -
*  0 1 0 => del
*  0 1 1 => keep same
*  1 0 x => new
*  1 1 x => chain
* */