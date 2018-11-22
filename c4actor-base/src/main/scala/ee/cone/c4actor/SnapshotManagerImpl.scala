package ee.cone.c4actor

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset

import SnapshotUtil._

object SnapshotUtil {
  def hashFromName: RawSnapshot⇒Option[SnapshotInfo] = {
    val R = """(snapshot[a-z_]+)/([0-9a-f]{16})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(-([0-9a-z]+))?""".r;
    {
      case raw@RawSnapshot(R(subDirStr,offsetHex,uuid,_,compressor)) ⇒ Option(SnapshotInfo(subDirStr,offsetHex,uuid,Option(compressor),raw))
      case a ⇒
        //logger.warn(s"not a snapshot: $a")
        None
    }
  }
  def hashFromData: Array[Byte]⇒String = UUID.nameUUIDFromBytes(_).toString
}

//case class Snapshot(offset: NextOffset, uuid: String, raw: RawSnapshot)
class SnapshotSaverImpl(subDirStr: String, inner: RawSnapshotSaver) extends SnapshotSaver {
  def save(offset: NextOffset, data: Array[Byte], compressor: String): RawSnapshot = {
    val snapshot = if (compressor.nonEmpty) RawSnapshot(s"$subDirStr/$offset-${hashFromData(data)}-$compressor") else RawSnapshot(s"$subDirStr/$offset-${hashFromData(data)}")
    assert(hashFromName(snapshot).nonEmpty)
    inner.save(snapshot, data)
    snapshot
  }
}

class SnapshotLoaderImpl(raw: RawSnapshotLoader, compressorRegistry: CompressorRegistry) extends SnapshotLoader with LazyLogging {
  def list: List[SnapshotInfo] = {
    val parseName = hashFromName
    val rawList = raw.list("snapshots")
    val res = rawList.flatMap(parseName(_)).sortBy(_.offset).reverse
    res
  }
  def load(snapshot: RawSnapshot): Option[RawEvent] = {
    logger.debug(s"Loading raw snapshot [${snapshot.relativePath}]")
    val res = for {
      snapshotInfo ← hashFromName(snapshot) //goes first, secures fs
      headers = snapshotInfo.compressor match {
        case None ⇒ Nil
        case Some(name) ⇒ compressorRegistry.byName(name).get.getRawHeaders
      }
      data ← Option(raw.load(snapshot)) if hashFromData(data.toByteArray) == snapshotInfo.uuid
    } yield SimpleRawEvent(snapshotInfo.offset, data, headers)
    logger.debug(s"Loaded raw snapshot ${res.nonEmpty}")
    res
  }
}

case class SnapshotLoaderFactoryImpl(compressorRegistry: CompressorRegistry) extends SnapshotLoaderFactory {
  def create(raw: RawSnapshotLoader): SnapshotLoader =
    new SnapshotLoaderImpl(raw, compressorRegistry)
}