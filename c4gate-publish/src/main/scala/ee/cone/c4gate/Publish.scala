package ee.cone.c4gate

import scala.collection.immutable.Seq
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import okio.{Buffer, ByteString, GzipSink}
import java.nio.charset.StandardCharsets.UTF_8

import com.typesafe.scalalogging.LazyLogging

import ee.cone.c4actor.Types._
import ee.cone.c4proto.ToByteString

//todo un-publish

@c4component @listed case class PublishInitialObserversProvider(
  compressor: Compressor,
  qMessages: QMessages, publishDir: PublishDir, publishConfig: PublishConfig,
  httpPublications: ByPK[HttpPublication] @c4key
)(
  observer: Observer = new PublishingObserver(compressor, qMessages,publishDir,publishConfig,httpPublications)
) extends InitialObserversProvider {
  def initialObservers: List[Observer] = List(observer)
}

@c4component case class DefaultPublishConfig() extends PublishConfig {
  def mimeTypes: Map[String,String] = Map( //not finished on gate-server side
    "html" → "text/html; charset=UTF-8",
    "js" → "application/javascript",
    "ico" → "image/x-icon"
  )
  def publishFromStrings: List[(String,String)] = Nil
}

@c4component case class DefaultPublishDir() extends PublishDir("htdocs")

class PublishingObserver(
  compressor: Compressor,
  qMessages: QMessages,
  fromDir: PublishDir,
  config: PublishConfig,
  httpPublications: ByPK[HttpPublication]
) extends Observer with LazyLogging {
  def activate(global: Context): Seq[Observer] = {
    logger.debug("publish started")
    val fromPath = Paths.get(fromDir.value)
    val visitor = new PublishFileVisitor(fromPath,publish(global))
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    config.publishFromStrings.foreach{ case(path,body) ⇒
      publish(global)(path,body.getBytes(UTF_8))
    }
    logger.debug("publish finished")
    Nil
  }
  def publish(local: Context)(path: String, body: Array[Byte]): Unit = {
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) None else Option(path.substring(pointPos+1))
    val headers = Header("Content-Encoding", compressor.name) ::
      ext.flatMap(config.mimeTypes.get).map(Header("Content-Type",_)).toList
    val byteString = compressor.compress(ToByteString(body))
    val publication = HttpPublication(path,headers,byteString,None)
    val existingPublications = httpPublications.of(local)
    //println(s"${existingPublications.getOrElse(path,Nil).size}")
    if(existingPublications.get(path).contains(publication)) {
      logger.debug(s"$path (${byteString.size}) exists")
    } else {
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