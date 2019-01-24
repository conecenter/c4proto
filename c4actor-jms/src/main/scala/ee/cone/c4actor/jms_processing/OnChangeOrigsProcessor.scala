package ee.cone.c4actor.jms_processing

import java.util.UUID

import ee.cone.c4actor.QProtocol.TxRef
import ee.cone.c4actor._
import ee.cone.c4actor.jms_processing.JmsProtocol.{ChangedOrig, ModelsChanged}

import scala.collection.immutable.Seq

class OnChangeOrigsProcessor(watchedOrigs: List[WatchedOrig], toUpdate: ToUpdate, qAdapterRegistry: QAdapterRegistry) extends UpdatesPreprocessor {

  lazy val watchedOrigsIds: Set[Long] = watchedOrigs.map(p => qAdapterRegistry.byName(p.className).id).toSet

  def process(
    updates: Seq[QProtocol.Update]
  ): Seq[QProtocol.Update] = {
    val randomSrcId = UUID.randomUUID().toString
    val changed = updates.filter(up => watchedOrigsIds.contains(up.valueTypeId))
    Seq(ModelsChanged(randomSrcId, changed.map(c => ChangedOrig(c.srcId, c.valueTypeId)).toList), TxRef(randomSrcId, "")).flatMap(LEvent.update).map(p => toUpdate.toUpdate(p))
  }

}
