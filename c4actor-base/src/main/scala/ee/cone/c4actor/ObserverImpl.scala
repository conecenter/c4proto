package ee.cone.c4actor

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{SrcId, TransientMap}

import scala.collection.immutable.{Map, Seq}

class TxTransforms(qMessages: QMessages) extends LazyLogging {
  type TransientTransform = TransientMap ⇒ TransientMap
  def get(global: Context): Map[SrcId,TransientTransform] =
    ByPK(classOf[TxTransform]).of(global).transform{ case(key,_) ⇒ handle(global,key) }
  private def handle(global: Context, key: SrcId): TransientTransform = ((prev:TransientMap) ⇒
    new Context(global.injected, global.assembled, prev)
  ).andThen{ (local:Context) ⇒
    val txTransform = ByPK(classOf[TxTransform]).of(local).get(key)
    if( //todo implement skip for outdated world
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

class LocalStateItem(val expireAt: Instant, val future: FatalFuture[TransientMap])

class ParallelObserver(
  localStates: Map[SrcId,LocalStateItem],
  transforms: TxTransforms,
  execution: Execution
) extends Observer {
  private def empty: FatalFuture[TransientMap] = execution.future(Map.empty)
  def activate(global: Context): Seq[Observer] = {
    val now = Instant.now
    val expireAt = now.plusSeconds(600)
    val inProgressMap = localStates.filterNot{ case(k,v) ⇒
      v.future.isCompleted && v.expireAt.isBefore(now)
    }
    val toAdd = transforms.get(global).transform{ case(key,handle) ⇒
      new LocalStateItem(expireAt, localStates.get(key).fold(empty)(_.future).map(handle))
    }
    val nLocalStates = inProgressMap ++ toAdd
    List(new ParallelObserver(nLocalStates,transforms,execution))
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