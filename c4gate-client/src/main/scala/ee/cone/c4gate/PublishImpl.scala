package ee.cone.c4gate

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPublication}
import ee.cone.c4proto.{ToByteString, c4}

import scala.collection.immutable.Seq

//todo un-publish

@c4("PublishingCompApp") class PublishingInitialObserverProvider(
  qMessages: QMessages,
  idGenUtil: IdGenUtil,
  publishFromStringsProviders: List[PublishFromStringsProvider],
  mimeTypesProviders: List[PublishMimeTypesProvider],
  compressor: PublishFullCompressor
)(
  publishDir: String = "htdocs",
  publishFromStrings: List[(String,String)] = publishFromStringsProviders.flatMap(_.get),
  getMimeType: String=>Option[String] = mimeTypesProviders.flatMap(_.get).toMap.get
) extends InitialObserverProvider(Option(new PublishingObserver(compressor.value,qMessages,idGenUtil,publishDir,publishFromStrings,getMimeType)))

class PublishingObserver(
  compressor: Compressor,
  qMessages: QMessages,
  idGenUtil: IdGenUtil,
  fromDir: String,
  fromStrings: List[(String,String)],
  mimeTypes: String=>Option[String]
) extends Observer[RichContext] with LazyLogging {
  def activate(global: RichContext): Seq[Observer[RichContext]] = {
    //println("AAA")
    logger.debug("publish started")
    val fromPath = Paths.get(fromDir)
    val visitor = new PublishFileVisitor(fromPath,publish(global))
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    fromStrings.foreach{ case(path,body) =>
      publish(global)(path,body.getBytes(UTF_8))
    }
    logger.debug("publish finished")
    //println("BBB")
    Nil
  }
  def publish(global: RichContext)(path: String, body: Array[Byte]): Unit = {
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) "" else path.substring(pointPos+1)
    val byteString = compressor.compress(ToByteString(body))
    val mimeType = mimeTypes(ext)
    val eTag = "v1-" +
      idGenUtil.srcIdFromSerialized(0,byteString) +
      idGenUtil.srcIdFromSerialized(0,ToByteString(s"${mimeType.getOrElse("")}:"))
    val headers =
      N_Header("etag", s""""$eTag"""") ::
      N_Header("content-encoding", compressor.name) ::
      mimeType.map(N_Header("content-type",_)).toList
    val publication = S_HttpPublication(path,headers,byteString,None)
    val existingPublications = ByPK(classOf[S_HttpPublication]).of(global)
    //println(s"${existingPublications.getOrElse(path,Nil).size}")
    if(existingPublications.get(path).contains(publication)) {
      logger.debug(s"$path (${byteString.size}) exists")
    } else {
      val local = new Context(global.injected, global.assembled, global.executionContext, Map.empty)
      TxAdd(LEvent.update(publication)).andThen(qMessages.send)(local)
      logger.debug(s"$path (${byteString.size}) published")
    }
  }
}

class PublishFileVisitor(
  fromPath: Path, publish: (String,Array[Byte])=>Unit
) extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val path = s"/${fromPath.relativize(file)}"
    val body = Files.readAllBytes(file)
    publish(path,body)
    FileVisitResult.CONTINUE
  }
}