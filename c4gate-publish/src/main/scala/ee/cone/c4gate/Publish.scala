package ee.cone.c4gate

import scala.collection.immutable.Seq
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import okio.{Buffer, ByteString, GzipSink}
import java.nio.charset.StandardCharsets.UTF_8

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4proto.ToByteString

//todo un-publish

class PublishingObserver(
  compressor: Compressor,
  qMessages: QMessages,
  idGenUtil: IdGenUtil,
  fromDir: String,
  fromStrings: List[(String,String)],
  mimeTypes: String⇒Option[String]
) extends Observer with LazyLogging {
  def activate(global: RichContext): Seq[Observer] = {
    //println("AAA")
    logger.debug("publish started")
    val fromPath = Paths.get(fromDir)
    val visitor = new PublishFileVisitor(fromPath,publish(global))
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    fromStrings.foreach{ case(path,body) ⇒
      publish(global)(path,body.getBytes(UTF_8))
    }
    logger.debug("publish finished")
    //println("BBB")
    Nil
  }
  def publish(global: RichContext)(path: String, body: Array[Byte]): Unit = {
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) None else Option(path.substring(pointPos+1))
    val byteString = compressor.compress(ToByteString(body))
    val headers =
      Header("ETag", s""""${idGenUtil.srcIdFromSerialized(0,byteString)}"""") ::
      Header("Content-Encoding", compressor.name) ::
      ext.flatMap(mimeTypes).map(Header("Content-Type",_)).toList
    val publication = HttpPublication(path,headers,byteString,None)
    val existingPublications = ByPK(classOf[HttpPublication]).of(global)
    //println(s"${existingPublications.getOrElse(path,Nil).size}")
    if(existingPublications.get(path).contains(publication)) {
      logger.debug(s"$path (${byteString.size}) exists")
    } else {
      val local = new Context(global.injected, global.assembled, Map.empty)
      TxAdd(LEvent.update(publication)).andThen(qMessages.send)(local)
      logger.debug(s"$path (${byteString.size}) published")
    }
  }
}





class PublishFileVisitor(
  fromPath: Path, publish: (String,Array[Byte])⇒Unit
) extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val path = s"/${fromPath.relativize(file)}"
    val body = Files.readAllBytes(file)
    publish(path,body)
    FileVisitResult.CONTINUE
  }
}