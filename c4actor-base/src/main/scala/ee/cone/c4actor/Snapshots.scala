package ee.cone.c4actor

import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import scala.collection.JavaConverters.iterableAsScalaIterableConverter


RawSnapshot.save(registry.updatesAdapter.encode(Updates("",updates)))


object RawSnapshot {
  private def dir = Paths.get("db_snapshots")
  private def hashFromName: String⇒Option[String] = {
    val R = """[0-9]{13}-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})""".r;
    {
      case R(uuid) ⇒ Option(uuid)
      case a ⇒
        println(a)
        None
    }
  }
  private def hashFromData: Array[Byte]⇒String = UUID.nameUUIDFromBytes(_).toString
  def save(data: Array[Byte]): Unit = {
    val filename = s"${System.currentTimeMillis}-${hashFromData(data)}"
    if(hashFromName(filename).isEmpty) throw new Exception
    if(!Files.exists(dir)) throw new Exception(s"$dir should be provided by volume manager")
    Files.write(dir.resolve(filename),data)
  }
  def loadRecent: Stream[(Path,Option[Array[Byte]])] = for{
    path ← tryWith(Files.newDirectoryStream(dir))(_.asScala.toList).sorted.reverse.toStream
    uuid ← hashFromName(path.getFileName.toString)
  } yield {
    val data = Files.readAllBytes(path)
    (path,if(hashFromData(data) == uuid) Option(data) else None)
  }
  private def tryWith[C<:AutoCloseable,T](c: C)(f: C⇒T) = try f(c) finally c.close()
}
