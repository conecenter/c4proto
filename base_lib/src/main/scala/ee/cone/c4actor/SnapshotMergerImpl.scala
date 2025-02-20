package ee.cone.c4actor

import ee.cone.c4di.c4
import ee.cone.c4actor.QProtocol._

@c4("ServerCompApp") final class SnapshotDifferImpl(
  toUpdate: ToUpdate, reducer: RichRawWorldReducer, updateMapUtil: UpdateMapUtil,
  snapshotPatchIgnoreRegistry: SnapshotPatchIgnoreRegistry,
) extends SnapshotDiffer {
  def diff(local: Context, targetFullSnapshot: RawEvent, addIgnore: Set[Long]): List[N_UpdateFrom] =
    diff(local, toUpdate.toUpdates(List(targetFullSnapshot),"diff-to"), addIgnore)
  def diff(local: Context, target: List[N_UpdateFrom], addIgnore: Set[Long]): List[N_UpdateFrom] =
    updateMapUtil.diff(reducer.toUpdates(local), target, snapshotPatchIgnoreRegistry.ignore ++ addIgnore)
}
