package ee.cone.c4gate

import scala.collection.immutable.Seq

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import okio.{Buffer, GzipSink}

import java.nio.charset.StandardCharsets.UTF_8

//todo un-publish

class PublishingObserver(
  qMessages: QMessages,
  reducer: Reducer,
  fromDir: String,
  fromStrings: List[(String,String)],
  mimeTypes: String⇒Option[String]
) extends Observer {
  def activate(ctx: ObserverContext): Seq[Observer] = {
    println("publish started")
    val fromPath = Paths.get(fromDir)
    val visitor = new PublishFileVisitor(fromPath,publish(ctx))
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    fromStrings.foreach{ case(path,body) ⇒
      publish(ctx)(path,body.getBytes(UTF_8))
    }
    println("publish finished")
    Nil
  }
  def publish(ctx: ObserverContext)(path: String, body: Array[Byte]): Unit = {
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) None else Option(path.substring(pointPos+1))
    val headers = Header("Content-Encoding", "gzip") ::
      ext.flatMap(mimeTypes).map(Header("Content-Type",_)).toList
    val sink = new Buffer
    val gzipSink = new GzipSink(sink)
    gzipSink.write(new Buffer().write(body), body.length)
    gzipSink.close()
    val byteString = sink.readByteString()
    val world = ctx.getWorld()
    val publication = HttpPublication(path,headers,byteString,None)
    val existingPublications = By.srcId(classOf[HttpPublication]).of(world)
    //println(s"${existingPublications.getOrElse(path,Nil).size}")
    if(existingPublications.getOrElse(path,Nil).contains(publication)) {
      println(s"$path (${byteString.size}) exists")
    } else {
      val local = reducer.createTx(world)(Map())
      LEvent.add(LEvent.update(publication)).andThen(qMessages.send)(local)
      println(s"$path (${byteString.size}) published")
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