package ee.cone.c4gate

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import ee.cone.c4actor._
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import okio.{Buffer, GzipSink}

//todo un-publish

class PublishingObserver(qMessages: QMessages, reducer: Reducer, fromDir: String, mimeTypesStr: String, then: ()⇒Unit = ()⇒()) extends Observer {
  def activate(getWorld: () ⇒ World): Seq[Observer] = {
    val mimeTypes = mimeTypesStr.split(":").grouped(2).map(l⇒l(0)→l(1)).toMap
    val createTx: World⇒World = local ⇒ reducer.createTx(getWorld())(local)
    val fromPath = Paths.get(fromDir)
    val visitor = new PublishFileVisitor(qMessages,createTx,fromPath,mimeTypes.get)
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    Nil
  }
}

class PublishFileVisitor(qMessages: QMessages, createTx: World⇒World, fromPath: Path, mimeTypes: String⇒Option[String]) extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val path = s"/${fromPath.relativize(file)}"
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) None else Option(path.substring(pointPos+1))
    val headers = Header("Content-Encoding", "gzip") ::
      mimeTypes(ext).map(Header("Content-Type",_)).toList
    val body = Files.readAllBytes(file)
    val sink = new Buffer
    val gzipSink = new GzipSink(sink)
    gzipSink.write(new Buffer().write(body), body.length)
    gzipSink.close()
    val byteString = sink.readByteString()
    val local = createTx(Map())
    val publication = HttpPublication(path,headers,byteString)
    val world = TxKey.of(local).world
    val existingPublications = By.srcId(classOf[HttpPublication]).of(world)
    if(existingPublications.getOrElse(path,Nil).contains(publication)) {
      println(s"$path (${byteString.size}) exists")
    } else {
      LEvent.add(LEvent.update(publication)).andThen(qMessages.send)(local)
      println(s"$path (${byteString.size}) published")
    }
    FileVisitResult.CONTINUE
  }
}