package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.N_TxRef
import ee.cone.c4actor.Types._
import ee.cone.c4di._

import scala.annotation.tailrec

class RevertPatch(val values: UpdateMap, val offset: NextOffset)

@c4("ServerCompApp") final class RevertImpl(
  consuming: Consuming,
  toUpdate: ToUpdate,
  getN_TxRef: GetByPK[N_TxRef],
  txAdd: LTxAdd
) extends Reverting with LazyLogging {
  @tailrec private def iteration(
    consumer: Consumer, wasPatch: RevertPatch, endOffset: NextOffset
  ): RevertPatch = {
    val events = consumer.poll()
    val willPatch = if(events.isEmpty) wasPatch else {
      val updates = toUpdate.toUpdates(events)
      val values = updates.foldLeft(wasPatch.values)(toUpdate.add)
      new RevertPatch(values,events.last.srcId)
    }
    if(willPatch.offset >= endOffset) willPatch
    else iteration(consumer, willPatch, endOffset)
  }
  def revert(offset: NextOffset): Context=>Context = {
    val patch = consuming.process(offset, consumer => {
      val wasPatch = new RevertPatch(Map.empty,"0" * OffsetHexSize())
      iteration(consumer, wasPatch, consumer.endOffset)
    })
    val updates = toUpdate.toUpdates(patch.values).map(toUpdate.revert)
    logger.info(s"reverting ${updates.size} items")
    WriteModelKey.modify(_.enqueueAll(updates))
  }
  def id = "CAN_REVERT_FROM"
  def getSavepoint: Context=>Option[NextOffset] =
    local => getN_TxRef.ofA(local).get(id).map(_.txId).filter(_.nonEmpty)
  def revertToSavepoint: Context=>Context =
    local => revert(getSavepoint(local).get).andThen(makeSavepoint)(local)
  def makeSavepoint: Context=>Context = txAdd.add(LEvent.update(N_TxRef(id,"")))
}
