package ee.cone.c4actor

import ee.cone.c4di._

@c4("RichDataCompApp") final class SnapshotPatchIgnoreRegistryImpl(
  items: List[GeneralSnapshotPatchIgnore],
  qAdapterRegistry: QAdapterRegistry,
)(
  val ignore: Set[Long] = items.map{
    case item: SnapshotPatchIgnore[_] =>
      qAdapterRegistry.byName(item.cl.getName).id
  }.toSet
) extends SnapshotPatchIgnoreRegistry
