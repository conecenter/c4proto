package ee.cone.c4gate

import ee.cone.c4actor.SnapshotInfo

trait SnapshotLister {
  def list: List[SnapshotInfo]
}

