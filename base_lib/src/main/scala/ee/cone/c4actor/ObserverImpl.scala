package ee.cone.c4actor

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ObserverProtocol.S_TxTrBlock
import ee.cone.c4actor.Types.{SrcId, TransientMap, TxEvents}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import ee.cone.c4proto.{Id, protocol}

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.annotation.tailrec
import scala.concurrent.Future

trait TxTransforms {
  def get(global: RichContext): Map[SrcId,TransientMap=>TransientMap]
}

@c4("ServerCompApp") final class DefLongTxWarnPeriod(config: ListConfig)
  extends LongTxWarnPeriod(Single.option(config.get("C4TX_WARN_PERIOD_MS")).fold(500L)(_.toLong))

@c4("ServerCompApp") final class TxTransformsImpl(
  qMessages: QMessages, warnPeriod: LongTxWarnPeriod, catchNonFatal: CatchNonFatal,
  getTxTransform: GetByPK[EnabledTxTr], injected: List[Injected], reducer: RichRawWorldReducer,
  txBlocking: TxBlocking, txAdd: LTxAdd, hReducer: TxHistoryReducer,
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
  // ReadAfterWriteOffsetKey is previous write tx, not just previous; and isFailed can be None, including for zero offset
  private def recentlyFailed(local: Context) =
    hReducer.isFailed(reducer.history(local), ReadAfterWriteOffsetKey.of(local)).contains(true)
  private def doHandle(global: RichContext, key: SrcId, setName: String=>Unit, prev: TransientMap): TransientMap =
    catchNonFatal {
        getTxTransform.ofA(global).get(key) match {
          case None => prev
          case Some(trE) =>
            val tr = trE.value
            val workTimer = NanoTimer()
            val clName = tr.getClass.getName
            val name = s"$clName-$key"
            setName(s"tx-from-${System.currentTimeMillis}-$name")
            val prepLocal = reducer.toLocal(global, prev + (SharedContextKey->injected) + (TxTrIdKey->Option(key)))
            val transformedLocal = Function.chain(
              if(recentlyFailed(prepLocal)) Seq((l:Context)=>txAdd.add(txBlocking.block(l))(l)) // .block makes tx itself, so next time it will not be recentlyFailed, but blocked
              else Seq(
                TxTransformOrigMeta(clName),
                tr.transform(_),
                (l:Context)=>txAdd.add(txBlocking.unblock(l))(l),
                ErrorKey.set(Nil),
              )
            )(prepLocal)
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

case object TxTrIdKey extends TransientLens[Option[SrcId]](None)

class PrevTxFailedException extends Exception
@c4("RichDataCompApp") final class TxTryImpl(
  txBlocking: TxBlocking, catchNonFatal: CatchNonFatal,
) extends TxTry {
  def apply[T](local: Context)(aTry: =>T)(hint: =>String)(aCatch: Throwable=>T): T =
    if(ErrorKey.of(local).nonEmpty) aCatch(ErrorKey.of(local).head)
    else if(txBlocking.isBlocked(local)) aCatch(new PrevTxFailedException)
    else catchNonFatal(aTry)(hint)(aCatch)
}

@protocol("ProtoApp") object ObserverProtocol {
  @Id(0x001F) case class S_TxTrBlock(
    @Id(0x0011) srcId: SrcId, // txTr
    @Id(0x001B) failureCount: Long,
  )
}

@c4("RichDataCompApp") final class TxBlocking(sleep: Sleep, getTxTrBlock: GetByPK[S_TxTrBlock]) {
  private def getBlock(local: Context) = getTxTrBlock.ofA(local).get(TxTrIdKey.of(local).get)
  def block(local: Context): TxEvents = {
    val failureCount = getBlock(local).fold(0L)(_.failureCount) + 1L
    LEvent.update(S_TxTrBlock(TxTrIdKey.of(local).get, failureCount)) ++ sleep.forSeconds(failureCount)
  }
  def unblock(local: Context): TxEvents = getBlock(local).toSeq.flatMap(LEvent.delete(_))
  def isBlocked(local: Context): Boolean = getBlock(local).nonEmpty
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

@c4("ParallelObserversApp") final class ParallelObserverExecutable(
  worldSource: WorldSource, execution: Execution, transforms: TxTransforms
) extends Executable with Early {
  private final class DoneEv(val key: SrcId, val value: TransientMap)
  private def toActions(world: RichContext): Map[SrcId,TransientMap=>TransientMap] =
    transforms.get(world).withDefaultValue(transient =>
      if(world.offset < InnerReadAfterWriteOffsetKey.of(transient)) transient else Map.empty
    )
  def run(): Unit = {
    val queue: Q = new LinkedBlockingQueue
    worldSource.doWith(queue, ()=>iteration(queue, Map.empty))
  }
  private class ActorState(
    val transient: TransientMap, val todo: Option[TransientMap=>TransientMap], val inProgress: Boolean
  )
  private type Q = BlockingQueue[Either[RichContext,DoneEv]]
  private def checkActivate(queue: Q, k: SrcId, st: ActorState): ActorState = if(st.inProgress || st.todo.isEmpty) st else {
    execution.fatal(Future(queue.put(Right(new DoneEv(k, st.todo.get(st.transient)))))(_))
    new ActorState(st.transient, None, true)
  }
  @tailrec private def iteration(
    queue: Q, wasStates: Map[SrcId,ActorState]
  ): Unit = iteration(queue, queue.take() match {
    case Left(world) =>
      val actions = toActions(world)
      (actions.keySet ++ wasStates.keySet).map{ k =>
        val wasState = wasStates.getOrElse(k,new ActorState(Map.empty, None, false))
        val action = actions(k)
        k -> checkActivate(queue, k, new ActorState(wasState.transient, Option(action), wasState.inProgress))
      }.toMap
    case Right(ev) =>
      val state = checkActivate(queue, ev.key, new ActorState(ev.value, wasStates(ev.key).todo, false))
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