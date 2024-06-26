package ee.cone.c4actor

import okio.ByteString

import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._

case class RawSnapshot(relativePath: String)
trait RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit
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
trait SnapshotSaverFactory {
  def create(subDirStr: String): SnapshotSaver
}
trait SnapshotUtil {
  def hashFromName: RawSnapshot=>Option[SnapshotInfo]
  def getName(subDirStr: String, offset: NextOffset, data: Array[Byte], headers: List[RawHeader]): String
}
case class SnapshotInfo(subDirStr: String, offset: NextOffset, uuid: String, headers: List[RawHeader], raw: RawSnapshot)
trait SnapshotLoader {
  def load(snapshot: RawSnapshot): Option[RawEvent]
}
trait SnapshotLoaderFactory {
  def create(raw: RawSnapshotLoader): SnapshotLoader
}

sealed abstract class SnapshotTask(val name: String, val offsetOpt: Option[NextOffset]) extends Product
case class NextSnapshotTask(offsetOptArg: Option[NextOffset]) extends SnapshotTask("next",offsetOptArg)
case class DebugSnapshotTask(offsetArg: NextOffset) extends SnapshotTask("debug",Option(offsetArg))

trait SnapshotTaskSigner extends Signer[SnapshotTask]

trait SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot]
}

trait RemoteSnapshotUtil {
  def request(appURL: String, signed: String): ()=>List[RawSnapshot]
}

trait SnapshotMerger {
  def merge(baseURL: String, signed: String): Context => Context
}

trait SnapshotDiffer {
  def diff(currentSnapshot: RawEvent, targetFullSnapshot: RawEvent, addIgnore: Set[Long]): List[N_UpdateFrom]
  def diff(currentSnapshot: RawEvent, target: List[N_UpdateFrom], addIgnore: Set[Long]): List[N_UpdateFrom]
  def needCurrentSnapshot: Context=>RawEvent
}

case class TimedSnapshotInfo(snapshot: SnapshotInfo, mTime: Long)

trait SnapshotLister {
  def list: List[SnapshotInfo]
  def listWithMTime: List[TimedSnapshotInfo]
}

trait SnapshotLast {
  def get: Option[RawSnapshot]
}
