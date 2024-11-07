package ee.cone.c4gate_server

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{LEvents, NextOffset}
import ee.cone.c4actor._
import ee.cone.c4di.c4

import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec

@c4("WorldProviderApp") final class TxSendImpl(
  toUpdate: ToUpdate, proc: OuterUpdateProcessor, util: UpdateFromUtil, qMessages: QMessages,
){
  def send(context: AssembledContext, lEvents: LEvents): NextOffset =
    qMessages.doSend(util.get(context, proc.process(lEvents.map(toUpdate.toUpdate), 0)).toList)
}

@c4("WorldProviderApp") final class WorldProviderImpl(
  worldSource: WorldSource, txSend: TxSendImpl
) extends WorldProvider with LazyLogging {
  import WorldProvider._
  def run(steps: Steps): Unit = {
    val queue = new LinkedBlockingQueue[Either[RichContext,Unit]]()
    @tailrec def iter(readAfterWriteOffsetOpt: Option[NextOffset], left: Steps): Unit = {
      val Left(world) = queue.take()
      if(readAfterWriteOffsetOpt.exists(world.offset < _)) iter(readAfterWriteOffsetOpt, left)
      else left.head(world) match {
        case Redo() => iter(readAfterWriteOffsetOpt, left)
        case Next(events) if events.isEmpty => throw new Exception()
        case Next(events) => iter(Option(txSend.send(world, events)), left.tail)
        case Stop() => ()
      }
    }
    worldSource.doWith(queue, () => iter(None, steps))
  }
}
