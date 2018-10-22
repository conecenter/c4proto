package ee.cone.c4actor

import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4actor.Types._
import ee.cone.c4proto.ToByteString
import okio.ByteString
import SnapshotUtil._

/*snapshot cleanup:
docker exec ... ls -la c4/db4/snapshots
docker logs ..._snapshot_maker_1
docker exec ... bash -c 'echo "30" > db4/snapshots/.ignore'
docker restart ..._snapshot_maker_1
*/

class FileSnapshotConfigImpl(dirStr: String)(val ignore: Set[Long] =
  Option(Paths.get(dirStr).resolve(".ignore")).filter(Files.exists(_)).toSet.flatMap{
    (path:Path) ⇒
      val content = new String(Files.readAllBytes(path), UTF_8)
      val R = """([0-9a-f]+)""".r
      R.findAllIn(content).map(java.lang.Long.parseLong(_, 16))
  }
) extends SnapshotConfig

class FileRawSnapshotLoader(dirStr: String) extends RawSnapshotLoader {
  def list: List[RawSnapshot] = {
    val dir = Paths.get(dirStr)
    if(!Files.exists(dir)) Nil
    else FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList)
      .map(new FileRawSnapshot(_))
  }
}

class FileRawSnapshot(path: Path) extends RawSnapshot with Removable with SnapshotTime {
  def name = path.getFileName.toString
  def load(): ByteString = ToByteString(Files.readAllBytes(path))
  def remove(): Unit = Files.delete(path)
  def mTime: Long = Files.getLastModifiedTime(path).toMillis
}

object SnapshotUtil {
  def hashFromName: String⇒Option[(String,String)] = {
    val R = """([0-9a-f]{16})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""".r;
    {
      case R(offsetHex,uuid) ⇒ Option((offsetHex,uuid))
      case a ⇒
        //logger.warn(s"not a snapshot: $a")
        None
    }
  }
  def hashFromData: Array[Byte]⇒String = UUID.nameUUIDFromBytes(_).toString
}

class SnapshotSaverImpl(inner: RawSnapshotSaver) extends SnapshotSaver {
  def save(
    offset: NextOffset,
    data: Array[Byte]
  ): Unit = {
    val filename = s"$offset-${hashFromData(data)}"
    if(hashFromName(filename).isEmpty) throw new Exception
    inner.save(filename, data)
  }
}

class SnapshotLoaderImpl(inner: RawSnapshotLoader) extends SnapshotLoader {
  def list: List[Snapshot] = {
    val parseName = hashFromName
    for {
      rawSnapshot ← inner.list
      (offsetStr,uuid) ← parseName(rawSnapshot.name)
    } yield new Snapshot(offsetStr,uuid,rawSnapshot,()⇒RawEvent(offsetStr,rawSnapshot.load()))
  }
}

class SnapshotLoadingRawWorldFactory(
  ignoreFrom: Option[NextOffset], loader: SnapshotLoader, inner: RawWorldFactory
) extends RawWorldFactory with LazyLogging {
  def create(): RawWorld = {
    val initialRawWorld = inner.create()
    (for{
      snapshot ← loader.list.sortBy(_.offset).reverse.toStream
        if ignoreFrom.forall(snapshot.offset < _)
      event ← {
        logger.info(s"Loading snapshot ${snapshot.raw.name}")
        Option(snapshot.load())
      } if hashFromData(event.data.toByteArray) == snapshot.uuid
      world ← Option(initialRawWorld.reduce(List(event))) if !world.hasErrors
    } yield {
      logger.info(s"Snapshot loaded")
      world
    }).headOption.getOrElse(initialRawWorld)
  }
}
