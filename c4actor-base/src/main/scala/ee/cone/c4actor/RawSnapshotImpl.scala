package ee.cone.c4actor

import java.nio.file.{Files, Paths}
import java.util.UUID

import scala.collection.JavaConverters.iterableAsScalaIterableConverter

//RawSnapshot.save(registry.updatesAdapter.encode(Updates("",updates)))

object RawSnapshotImpl extends RawSnapshot {
  private def dir = Paths.get("db_snapshots")
  private def hashFromName: String⇒Option[(String,String)] = {
    val R = """([0-9a-f]{16})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""".r;
    {
      case R(offsetHex,uuid) ⇒ Option((offsetHex,uuid))
      case a ⇒
        println(a)
        None
    }
  }
  private def hashFromData: Array[Byte]⇒String = UUID.nameUUIDFromBytes(_).toString
  def save(data: Array[Byte], offset: Long): Unit = {
    val offsetHex = (("0" * 16)+java.lang.Long.toHexString(offset)).takeRight(16)
    val filename = s"$offsetHex-${hashFromData(data)}"
    if(hashFromName(filename).isEmpty) throw new Exception
    if(!Files.exists(dir)) throw new Exception(s"$dir should be provided by volume manager")
    Files.write(dir.resolve(filename),data)
  }
  private def loadRecentStream: Stream[(Long,Option[Array[Byte]])] = for{
    path ← FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList).sorted.reverse.toStream
    (offsetStr,uuid) ← hashFromName(path.getFileName.toString)
  } yield {
    val data = Files.readAllBytes(path)
    val offset = java.lang.Long.parseLong(offsetStr,16)
    (offset, if(hashFromData(data) == uuid) Option(data) else None)
  }
  def loadRecent: RawObserver ⇒ RawObserver = observer ⇒ // cg mg
    loadRecentStream.flatMap { case (offset, dataOpt) ⇒
      println(s"Loading snapshot up to $offset")
      dataOpt.map(observer.reduce(_,offset)).filterNot(_.hasErrors)
    }.headOption.map{ res ⇒
      println(s"Snapshot loaded")
      res
    }.getOrElse(observer)
}
