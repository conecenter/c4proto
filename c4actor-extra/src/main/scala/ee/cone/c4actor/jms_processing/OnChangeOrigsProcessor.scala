package ee.cone.c4actor.jms_processing

import ee.cone.c4actor.jms_processing.JmsProtocol.ModelChangedMarker
import ee.cone.c4actor.{UpdatesPreprocessor, QAdapterRegistry, QProtocol, ToUpdate}

import scala.collection.immutable.Seq

class OnChangeOrigsProcessor(watchedOrigs: List[Class[_ <: Product]], toUpdate: ToUpdate, qAdapterRegistry: QAdapterRegistry) extends UpdatesPreprocessor {

  lazy val watchedOrigsIds: List[Long] = watchedOrigs.map(p => qAdapterRegistry.byName(p.getName).id)

  def process(
    updates: Seq[QProtocol.Update]
  ): Seq[QProtocol.Update] = {
    val changed = updates.filter(up => watchedOrigsIds.contains(up.valueTypeId))
    changed.map(p => ModelChangedMarker(p.srcId))
  }

}
