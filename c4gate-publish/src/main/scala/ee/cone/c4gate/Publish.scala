package ee.cone.c4gate

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import ee.cone.c4actor._
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import okio.{Buffer, GzipSink}

//todo un-publish

class PublishingObserver(
  qMessages: QMessages,
  reducer: Reducer,
  fromDir: String,
  mimeTypes: String⇒Option[String],
  thenStop: Boolean
) extends Observer {
  def activate(ctx: ObserverContext): Seq[Observer] = {
    println("publish started")
    val fromPath = Paths.get(fromDir)
    val visitor = new PublishFileVisitor(qMessages,reducer,ctx.getWorld,fromPath,mimeTypes)
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    println("publish finished")
    if(thenStop) ctx.executionContext.complete(None)
    Nil
  }
}

class PublishFileVisitor(
  qMessages: QMessages,
  reducer: Reducer,
  getWorld: () ⇒ World,
  fromPath: Path,
  mimeTypes: String⇒Option[String]
) extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val path = s"/${fromPath.relativize(file)}"
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) None else Option(path.substring(pointPos+1))
    val headers = Header("Content-Encoding", "gzip") ::
      ext.flatMap(mimeTypes).map(Header("Content-Type",_)).toList
    val body = Files.readAllBytes(file)
    val sink = new Buffer
    val gzipSink = new GzipSink(sink)
    gzipSink.write(new Buffer().write(body), body.length)
    gzipSink.close()
    val byteString = sink.readByteString()
    val world = getWorld()
    val publication = HttpPublication(path,headers,byteString)
    val existingPublications = By.srcId(classOf[HttpPublication]).of(world)
    //println(s"${existingPublications.getOrElse(path,Nil).size}")
    if(existingPublications.getOrElse(path,Nil).contains(publication)) {
      println(s"$path (${byteString.size}) exists")
    } else {
      val local = reducer.createTx(world)(Map())
      LEvent.add(LEvent.update(publication)).andThen(qMessages.send)(local)
      println(s"$path (${byteString.size}) published")
    }
    FileVisitResult.CONTINUE
  }
}