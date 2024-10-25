package ee.cone.c4gate_server

import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4actor._
import ee.cone.c4di.c4

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Success, Try}

@c4("WorldProviderApp") final class WorldProviderImpl(
  txAdd: LTxAdd, qMessages: QMessages, worldSource: WorldSource
) extends WorldProvider {
  def tx[R](cond: RichContext=>Boolean)(
    f: Context=>(List[LEvent[Product]],R)
  )(implicit executionContext: ExecutionContext): Future[TxRes[R]] =
    for(context <- worldSource.take(c => Option(c).filter(cond))) yield {
      val local = new Context(context.injected,context.assembled,context.executionContext,Map.empty)
      val (events,res) = f(local)
      val nLocal = txAdd.add(events)(local)
      new TxRes(res, makeNextCond(context.offset, ReadAfterWriteOffsetKey.of(qMessages.send(nLocal))))
    }
  private def makeNextCond(wasOffset: NextOffset, readAfterWriteOffset: NextOffset): RichContext=>Boolean =
    context => context.offset > wasOffset && context.offset >= readAfterWriteOffset
  // todo: fix? if TxAdd had done nothing, offset will be minimal,
  //  but promise does not resolve instantly;
  // ex. pong can take 200ms until the next WorldProviderMessage
}

@c4("WorldProviderApp") final class WorldSourceImpl(
) extends WorldSource with TxObserver with Executable with Early {
  private sealed trait WorldMessage
  private class TakeMessage[T](val by: RichContext=>Option[T], val promise: Promise[T]) extends WorldMessage {
    def activate(world: RichContext): Unit =
      try{ by(world).foreach(promise.success) } catch { case NonFatal(e) => promise.failure(e) }
  }
  private class RichMessage(val world: RichContext) extends WorldMessage
  private val queue = new LinkedBlockingQueue[WorldMessage]
  def activate(world: RichContext): Unit = queue.put(new RichMessage(world))
  def run(): Unit = iteration(Nil)
  @tailrec private def iteration(waitList: List[TakeMessage[_]]): Unit = queue.take() match {
    case m: RichMessage => iteration(m.world, waitList, Nil)
    case m: TakeMessage[_] => iteration(m :: waitList)
  }
  @tailrec private def iteration(world: RichContext, chkList: List[TakeMessage[_]], noChkList: List[TakeMessage[_]]): Unit = {
    for(m <- chkList) m.activate(world)
    val waitList = chkList.filterNot(_.promise.isCompleted) ::: noChkList
    queue.take() match {
      case m: RichMessage => iteration(m.world, waitList, Nil)
      case m: TakeMessage[_] => iteration(world, List(m), waitList)
    }
  }
  def take[T](by: RichContext=>Option[T]): Future[T] = {
    val promise = Promise[T]()
    queue.put(new TakeMessage[T](by, promise))
    promise.future
  }
}
