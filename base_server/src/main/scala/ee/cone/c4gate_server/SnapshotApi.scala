package ee.cone.c4gate_server

import ee.cone.c4actor.SnapshotInfo

trait SnapshotRemover {
  def deleteIfExists(snapshot: SnapshotInfo): Boolean
}

