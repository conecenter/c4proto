package ee.cone.c4actor

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{SrcId, TransientMap}
import ee.cone.c4assemble.Types._

import scala.collection.immutable.{Map, Seq}
import scala.util.{Success, Try}

class TxTransforms(qMessages: QMessages) extends LazyLogging {
  def get(global: Context): Map[SrcId,Option[Context]⇒Context] =
    ByPK(classOf[TxTransform]).of(global).keys.map(k⇒k→handle(global,k)).toMap
  private def handle(global: Context, key: SrcId): Option[Context]⇒Context = {
    val enqueueTimer = NanoTimer()
    prevOpt ⇒
    val local = prevOpt.getOrElse(new Context(global.injected, emptyReadModel, Map.empty))
    val startLatency = enqueueTimer.ms
    if(startLatency > 200)
      logger.debug(s"tx $key start latency $startLatency ms")
    val workTimer = NanoTimer()
    val res = if( //todo implement skip for outdated world
        OffsetWorldKey.of(global) < OffsetWorldKey.of(local) ||
      Instant.now.isBefore(SleepUntilKey.of(local))
    ) local else try {
      Trace {
        ByPK(classOf[TxTransform]).of(global).get(key) match {
          case None ⇒ local
          case Some(tr) ⇒
            val prepLocal = new Context(global.injected, global.assembled, local.transient)
            val nextLocal = (tr.transform _).andThen(qMessages.send)(prepLocal)
            new Context(global.injected, emptyReadModel, nextLocal.transient)
        }
      }
    } catch {
      case exception: Exception ⇒
        logger.error(s"Tx failed [$key][${Thread.currentThread.getName}]",exception)
        val was = ErrorKey.of(local)
        Function.chain(List(
          ErrorKey.set(exception :: was),
          SleepUntilKey.set(Instant.now.plusSeconds(was.size))
        ))(new Context(global.injected, emptyReadModel, Map.empty))
    }
    val period = workTimer.ms
    if(period > 500)
      logger.debug(s"tx $key worked for $period ms")
    res
  }
}

class SerialObserver(localStates: Map[SrcId,Context])(
  transforms: TxTransforms
) extends Observer {
  def activate(global: Context): Seq[Observer] = {
    val nLocalStates = transforms.get(global).transform{ case(key,handle) ⇒
      handle(localStates.get(key))
    }
    List(new SerialObserver(nLocalStates)(transforms))
  }
}



class ParallelObserver(
  localStates: Map[SrcId,FatalFuture[Option[Context]]],
  transforms: TxTransforms,
  execution: Execution
) extends Observer with LazyLogging {
  private def empty: FatalFuture[Option[Context]] = execution.future(None)
  def activate(global: Context): Seq[Observer] = {
    val inProgressMap = localStates.filter{ case(k,v) ⇒
      v.value match {
        case None ⇒ true // inProgress
        case Some(Success(Some(local))) ⇒
          OffsetWorldKey.of(global) < OffsetWorldKey.of(local)
        case a ⇒ throw new Exception(s"$a")
      }
    }
    val toAdd = transforms.get(global).transform{ case(key,handle) ⇒
      localStates.getOrElse(key,empty).map(opt⇒Option(handle(opt)))
    }
    val nLocalStates = inProgressMap ++ toAdd
    logger.debug(
      s"txTr count: ${nLocalStates.size}, " +
      s"inProgress: ${inProgressMap.size}, " +
      s"uncompleted: ${inProgressMap.values.count(_.value.isEmpty)}, " +
      s"just-mapped: ${toAdd.size}"
    )
    List(new ParallelObserver(nLocalStates,transforms,execution))
  }
}

/*
finished ok started deferred
None => keep
Some(Success(Some(local))) if global < local => keep
Some(Success(Some(local))) => not keep
Some(Failure(err)) => exit

 */

/* todo world provider?
world
world changed
all jobs
job finished(offset)
 */


/*
* in trans? in was? !isDone?
*  0 0 x => -
*  0 1 0 => del
*  0 1 1 => keep same
*  1 0 x => new
*  1 1 x => chain
* */