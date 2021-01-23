package ee.cone.c4gate_server

import ee.cone.c4actor.SnapshotInfo

case class TimedSnapshotInfo(snapshot: SnapshotInfo, mTime: Long)

trait SnapshotLister {
  def list: List[SnapshotInfo]
  def listWithMTime: List[TimedSnapshotInfo]
}

trait SnapshotRemover {
  def deleteIfExists(snapshot: SnapshotInfo): Boolean
}

