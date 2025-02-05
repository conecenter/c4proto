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
  txAdd: LTxAdd,
  updateMapUtil: UpdateMapUtil,
  ignoreRegistry: SnapshotPatchIgnoreRegistry,
) extends Reverting with LazyLogging {
  def revert(offset: NextOffset): Context=>Context = { // skips the offset event
    val patch = consuming.process(offset, consumer => { // todo fix; now seems to skip ALL events if starting one is expired
      val endOffset = consumer.endOffset
      logger.info(s"endOffset ${endOffset}")
      @tailrec def iteration(was: UpdateMapping): UpdateMapping = {
        val events = consumer.poll()
        val updates = toUpdate.toUpdates(events,"revert")
        val will = was.add(updates)
        if(events.exists(_.srcId>=endOffset)) will else iteration(will)
      }
      iteration(updateMapUtil.startRevert(ignoreRegistry.ignore))
    })
    val updates = patch.result
    WriteModelKey.modify(_.enqueueAll(updates))
  }
  def id = "CAN_REVERT_FROM"
  def getSavepoint: Context=>Option[NextOffset] =
    local => getN_TxRef.ofA(local).get(id).map(_.txId).filter(_.nonEmpty)
  def revertToSavepoint: Context=>Context =
    local => revert(getSavepoint(local).get).andThen(txAdd.add(makeSavepoint))(local)
  def makeSavepoint: LEvents = LEvent.update(N_TxRef(id,""))
}

@c4("ServerCompApp") final class SnapshotPatchIgnoreRegistryImpl(
  items: List[GeneralSnapshotPatchIgnore],
  qAdapterRegistry: QAdapterRegistry,
)(
  val ignore: Set[Long] = items.map{
    case item: SnapshotPatchIgnore[_] =>
      qAdapterRegistry.byName(item.cl.getName).id
  }.toSet
) extends SnapshotPatchIgnoreRegistry
