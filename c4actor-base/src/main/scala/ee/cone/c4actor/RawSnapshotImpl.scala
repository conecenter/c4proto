package ee.cone.c4actor

import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import java.nio.charset.StandardCharsets.UTF_8

import ee.cone.c4actor.QProtocol.{Update, Updates}
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4proto.ToByteString

//RawSnapshot.save(registry.updatesAdapter.encode(Updates("",updates)))

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

class FileRawSnapshotImpl(dirStr: String, rawWorldFactory: RawWorldFactory) extends RawSnapshot with LazyLogging {
  private def dir = Files.createDirectories(Paths.get(dirStr))
  private def hashFromName: String⇒Option[(String,String)] = {
    val R = """([0-9a-f]{16})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""".r;
    {
      case R(offsetHex,uuid) ⇒ Option((offsetHex,uuid))
      case a ⇒
        logger.warn(s"not a snapshot: $a")
        None
    }
  }
  private def hashFromData: Array[Byte]⇒String = UUID.nameUUIDFromBytes(_).toString
  def save(data: Array[Byte], offset: NextOffset): Unit = {
    val filename = s"$offset-${hashFromData(data)}"
    if(hashFromName(filename).isEmpty) throw new Exception
    Files.write(dir.resolve(filename),data)
  }
  private def loadRecentStream: Stream[(NextOffset,Option[Array[Byte]])] = for{
    path ← FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList).sorted.reverse.toStream
    (offsetStr,uuid) ← hashFromName(path.getFileName.toString)
  } yield {
    val data = Files.readAllBytes(path)
    (offsetStr, if(hashFromData(data) == uuid) Option(data) else None)
  }
  def loadRecent(): RawWorld = {
    val initialRawWorld = rawWorldFactory.create()
    loadRecentStream.flatMap { case (offset, dataOpt) ⇒
      logger.info(s"Loading snapshot up to $offset")
      dataOpt.map(data ⇒
        initialRawWorld.reduce(List(RawEvent(offset, ToByteString(data))))
      ).filterNot(_.hasErrors)
    }.headOption.map{ res ⇒
      logger.info(s"Snapshot loaded")
      res
    }.getOrElse(initialRawWorld)
  }

}
