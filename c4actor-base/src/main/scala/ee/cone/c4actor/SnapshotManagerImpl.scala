package ee.cone.c4actor

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset

import SnapshotUtil._

object SnapshotUtil {
  def hashFromName: RawSnapshot⇒Option[SnapshotInfo] = {
    val R = """(snapshot[a-z_]+)/([0-9a-f]{16})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""".r;
    {
      case raw@RawSnapshot(R(subDirStr,offsetHex,uuid)) ⇒ Option(SnapshotInfo(subDirStr,offsetHex,uuid,raw))
      case a ⇒
        //logger.warn(s"not a snapshot: $a")
        None
    }
  }
  def hashFromData: Array[Byte]⇒String = UUID.nameUUIDFromBytes(_).toString
}

//case class Snapshot(offset: NextOffset, uuid: String, raw: RawSnapshot)
class SnapshotSaverImpl(subDirStr: String, inner: RawSnapshotSaver) extends SnapshotSaver {
  def save(offset: NextOffset, data: Array[Byte]): RawSnapshot = {
    val snapshot = RawSnapshot(s"$subDirStr/$offset-${hashFromData(data)}")
    assert(hashFromName(snapshot).nonEmpty)
    inner.save(snapshot, data)
    snapshot
  }
}

class SnapshotLoaderImpl(raw: RawSnapshotLoader) extends SnapshotLoader with LazyLogging {
  def list: List[SnapshotInfo] = {
    val parseName = hashFromName
    raw.list("snapshots").flatMap(parseName(_)).sortBy(_.offset).reverse
  }
  def load(snapshot: RawSnapshot): Option[RawEvent] = {
    logger.info(s"Loading snapshot $snapshot")
    for {
      snapshotInfo ← hashFromName(snapshot) //goes first, secures fs
      data ← Option(raw.load(snapshot)) if hashFromData(data.toByteArray) == snapshotInfo.uuid
    } yield SimpleRawEvent(snapshotInfo.offset,data)
  }
}
