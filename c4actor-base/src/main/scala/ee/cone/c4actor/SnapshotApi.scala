package ee.cone.c4actor

import ee.cone.c4actor.Types.NextOffset
import okio.ByteString

case class RawSnapshot(relativePath: String)
trait RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit
}
trait RawSnapshotLoader {
  def list(subDirStr: String): List[RawSnapshot]
  def load(snapshot: RawSnapshot): ByteString
}
trait RawSnapshotLoaderFactory {
  def create(source: String): RawSnapshotLoader
}

trait SnapshotSaver {
  def save(offset: NextOffset, data: Array[Byte]): RawSnapshot
}
case class SnapshotInfo(subDirStr: String, offset: NextOffset, uuid: String, compressed: Boolean, raw: RawSnapshot)
trait SnapshotLoader {
  def load(snapshot: RawSnapshot): Option[RawEvent]
  def list: List[SnapshotInfo]
}
trait SnapshotLoaderFactory {
  def create(raw: RawSnapshotLoader): SnapshotLoader
}

sealed abstract class SnapshotTask(val name: String, val offsetOpt: Option[NextOffset]) extends Product
case class NextSnapshotTask(offsetOptArg: Option[NextOffset]) extends SnapshotTask("next",offsetOptArg)
case class DebugSnapshotTask(offsetArg: NextOffset) extends SnapshotTask("debug",Option(offsetArg))

trait SnapshotMaker {
  def make(task: SnapshotTask): ()⇒List[RawSnapshot]
}
trait SnapshotMakerFactory {
  def create(source: String): SnapshotMaker
}
trait SnapshotMerger {
  def merge(source: String, task: SnapshotTask): Context⇒Context
}
