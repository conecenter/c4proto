package ee.cone.c4actor

import ee.cone.c4actor.Types.NextOffset
import okio.ByteString

case class RawSnapshot(relativePath: String)
trait RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit
}
trait RawSnapshotLister {
  def list(subDirStr: String): List[RawSnapshot]
}
trait RawSnapshotLoader {
  def load(snapshot: RawSnapshot): ByteString
}
trait RawSnapshotLoaderFactory {
  def create(baseURL: String): RawSnapshotLoader
}

trait SnapshotSaver {
  def save(offset: NextOffset, data: Array[Byte], headers: List[RawHeader]): RawSnapshot
}
case class SnapshotInfo(subDirStr: String, offset: NextOffset, uuid: String, headers: List[RawHeader], raw: RawSnapshot)
trait SnapshotLister {
  def list: List[SnapshotInfo]
}
trait SnapshotLoader {
  def load(snapshot: RawSnapshot): Option[RawEvent]
}
trait SnapshotLoaderFactory {
  def create(raw: RawSnapshotLoader): SnapshotLoader
}

sealed abstract class SnapshotTask(val name: String, val offsetOpt: Option[NextOffset]) extends Product
case class NextSnapshotTask(offsetOptArg: Option[NextOffset]) extends SnapshotTask("next",offsetOptArg)
case class DebugSnapshotTask(offsetArg: NextOffset) extends SnapshotTask("debug",Option(offsetArg))

trait SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot]
}

trait RemoteSnapshotUtil {
  def request(appURL: String, signed: String): ()⇒List[RawSnapshot]
}

trait SnapshotMerger {
  def merge(baseURL: String, signed: String): Context⇒Context
}
