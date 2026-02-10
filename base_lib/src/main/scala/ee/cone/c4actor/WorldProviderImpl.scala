package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{NextOffset, TxEvents}
import WorldProvider._
import ee.cone.c4actor.QProtocol.S_FailedUpdates
import ee.cone.c4di.c4

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec

@c4("WorldProviderApp") final class TxSendImpl(
  toUpdate: ToUpdate, proc: OuterUpdateProcessor, util: UpdateFromUtil, qMessages: QMessages,
){
  def send(context: AssembledContext, events: TxEvents): NextOffset = {
    val updates = events.collect{ case e: LEvent[_] => toUpdate.toUpdate(e) }
    val rawUpdates = events.collect{ case e: RawTxEvent =>e.value }
    for(e <- events.collect{ case e: TransientEvent[_] => e }) throw new Exception(s"not supported $e")
    qMessages.doSend(util.get(context, proc.process(updates, 0)).toList ++ rawUpdates)
  }
}

@c4("WorldProviderApp") final class WorldProviderImpl(
  worldSource: WorldSource, txSend: TxSendImpl, txChecker: TxChecker,
) extends WorldProvider with LazyLogging {
  private def ready(world: RichContext, readAfterWriteOffsetOpt: Option[NextOffset]): Boolean =
    !readAfterWriteOffsetOpt.exists(world.offset < _)
  def run[R](steps: Steps[R]): R = {
    val queue = new LinkedBlockingQueue[Either[RichContext,Unit]]()
    @tailrec def iter(readAfterWriteOffsetOpt: Option[NextOffset], left: Steps[R]): R = {
      val Left(world) = queue.take()
      if(!ready(world, readAfterWriteOffsetOpt)) iter(readAfterWriteOffsetOpt, left)
      else {
        for(offset <- readAfterWriteOffsetOpt if txChecker.isFailed(world, offset)) throw new Exception("tx failed")
        left.head(world) match {
          case Redo() => iter(readAfterWriteOffsetOpt, left)
          case Next(events) if events.isEmpty => throw new Exception()
          case Next(events) => iter(Option(txSend.send(world, events)), left.tail)
          case Stop(r) => r
        }
      }
    }
    worldSource.doWith[Unit,R](queue, () => iter(None, steps))
  }
  def runUpdCheck(f: AssembledContext=>TxEvents): Unit = run(List(
    world => f(world) match { case Seq() => Stop() case lEvents => Next(lEvents) }, _ => Stop()
  ):Steps[Unit])
}

// it is minimal implementation w/o TxHistory replica exchange
@c4("WorldProviderApp") final class TxChecker(getS_FailedUpdates: GetByPK[S_FailedUpdates]) {
  def isFailed(context: RichContext, offset: NextOffset): Boolean = getS_FailedUpdates.ofA(context).contains(offset)
}
