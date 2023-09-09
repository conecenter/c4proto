package ee.cone.c4actor

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{SrcId, TransientMap}
import ee.cone.c4di.{c4, provide}

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec
import scala.collection.immutable.{Map, Seq}
import scala.concurrent.Future

trait TxTransforms {
  def get(global: RichContext): Map[SrcId,TransientMap=>TransientMap]
}

@c4("ServerCompApp") final class DefLongTxWarnPeriod extends LongTxWarnPeriod(Option(System.getenv("C4TX_WARN_PERIOD_MS")).fold(500L)(_.toLong))

@c4("ServerCompApp") final class TxTransformsImpl(
  qMessages: QMessages, warnPeriod: LongTxWarnPeriod, catchNonFatal: CatchNonFatal,
  getTxTransform: GetByPK[EnabledTxTr],
) extends TxTransforms with LazyLogging {
  def get(global: RichContext): Map[SrcId,TransientMap=>TransientMap] =
    getTxTransform.ofA(global).keys.map(k=>k->handle(global,k)).toMap
  private def handle(global: RichContext, key: SrcId): TransientMap=>TransientMap = {
    val enqueueTimer = NanoTimer()
    prev =>
    val startLatency = enqueueTimer.ms
    if(startLatency > 200)
      logger.debug(s"tx $key start latency $startLatency ms")
    if( //todo implement skip for outdated world
      global.offset < InnerReadAfterWriteOffsetKey.of(prev) ||
      Instant.now.isBefore(InnerSleepUntilKey.of(prev))
    ) prev else localizeThreadName(setName=>doHandle(global,key,setName,prev))
  }

  private def localizeThreadName[T](f: (String=>Unit)=>T): T = {
    val thread = Thread.currentThread
    val was = thread.getName
    try f(thread.setName) finally thread.setName(was)
  }

  private def doHandle(global: RichContext, key: SrcId, setName: String=>Unit, prev: TransientMap): TransientMap =
    catchNonFatal {
        getTxTransform.ofA(global).get(key) match {
          case None => prev
          case Some(trE) =>
            val tr = trE.value
            val workTimer = NanoTimer()
            val name = s"${tr.getClass.getName}-$key"
            setName(s"tx-from-${System.currentTimeMillis}-$name")
            val prepLocal = new Context(global.injected, global.assembled, global.executionContext, prev)
            val transformedLocal = TxTransformOrigMeta(tr.getClass.getName).andThen(tr.transform)(prepLocal)
            val transformPeriod = workTimer.ms
            val nextLocal = qMessages.send(transformedLocal)
            val period = workTimer.ms
            if(period > warnPeriod.value)
              logger.warn(s"tx $name worked for $period ms (transform $transformPeriod ms)")
            nextLocal.transient
        }
    }(s"Tx failed [$key][${Thread.currentThread.getName}]"){ e =>
        val was = InnerErrorKey.of(prev)
        val exception = e match {
          case e: Exception => e
          case err => new Exception(err)
        }
        Function.chain(List(
          InnerErrorKey.set(exception :: was),
          InnerSleepUntilKey.set(Instant.now.plusSeconds(was.size))
        ))(Map.empty)
    }
}

case object InnerErrorKey extends InnerTransientLens(ErrorKey)
case object InnerSleepUntilKey extends InnerTransientLens(SleepUntilKey)
case object InnerReadAfterWriteOffsetKey extends InnerTransientLens(ReadAfterWriteOffsetKey)

abstract class InnerTransientLens[Item](key: TransientLens[Item]) extends AbstractLens[TransientMap,Item] with Product {
  def of: TransientMap => Item =
    m => m.getOrElse(key, key.default).asInstanceOf[Item]
  def set: Item => TransientMap=>TransientMap =
    value => m => m + (key -> value.asInstanceOf[Object])
}

class TxObserver(val value: Observer[RichContext])

object InnerNoTxObserver extends Observer[RichContext] {
  def activate(world: RichContext): Observer[RichContext] = this
}
@c4("NoObserversApp") final class NoTxObserver extends TxObserver(InnerNoTxObserver)

@c4("SerialObserversApp") final class SerialTxObserver(
  transforms: TxTransforms
) extends TxObserver(new SerialObserver(Map.empty)(transforms))

class SerialObserver(localStates: Map[SrcId,TransientMap])(
  transforms: TxTransforms
) extends Observer[RichContext] {
  def activate(global: RichContext): Observer[RichContext] = {
    val nLocalStates = transforms.get(global).transform{ case(key,handle) =>
      handle(localStates.getOrElse(key,Map.empty))
    }
    new SerialObserver(nLocalStates)(transforms)
  }
}

@c4("ParallelObserversApp") final class ParallelObserverProvider(
  transforms: TxTransforms,
  ex: ParallelObserverExecutable
) {
  @provide def observers: Seq[TxObserver] = Seq(new TxObserver(new Observer[RichContext] {
    def activate(world: RichContext): Observer[RichContext] = {
      ex.send(transforms.get(world).withDefaultValue(transient =>
        if(world.offset < InnerReadAfterWriteOffsetKey.of(transient)) transient else Map.empty
      ))
      this
    }
  }))
}

@c4("ParallelObserversApp") final class ParallelObserverExecutable(
  execution: Execution
) extends Executable with Early {
  private sealed trait ObservedEvent
  private final class TodoEv(val value: Map[SrcId, TransientMap => TransientMap]) extends ObservedEvent
  private final class DoneEv(val key: SrcId, val value: TransientMap) extends ObservedEvent
  private val queue = new LinkedBlockingQueue[ObservedEvent]
  def send(actions: Map[SrcId,TransientMap=>TransientMap]): Unit =
    queue.put(new TodoEv(actions))
  def run(): Unit = iteration(Map.empty)
  private class ActorState(
    val transient: TransientMap, val todo: Option[TransientMap=>TransientMap], val inProgress: Boolean
  )
  private def checkActivate(k: SrcId, st: ActorState): ActorState = if(st.inProgress || st.todo.isEmpty) st else {
    execution.fatal(Future(queue.put(new DoneEv(k, st.todo.get(st.transient))))(_))
    new ActorState(st.transient, None, true)
  }
  @tailrec private def iteration(wasStates: Map[SrcId,ActorState]): Unit = iteration(queue.take() match {
    case ev: TodoEv =>
      (ev.value.keySet ++ wasStates.keySet).map{ k =>
        val wasState = wasStates.getOrElse(k,new ActorState(Map.empty, None, false))
        val action = ev.value(k)
        k -> checkActivate(k, new ActorState(wasState.transient, Option(action), wasState.inProgress))
      }.toMap
    case ev: DoneEv =>
      val state = checkActivate(ev.key, new ActorState(ev.value, wasStates(ev.key).todo, false))
      if(state.transient.isEmpty && state.todo.isEmpty && !state.inProgress)
        wasStates - ev.key  else wasStates + (ev.key->state)
  })
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