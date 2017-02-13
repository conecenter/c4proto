package ee.cone.c4gate

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._
import java.util.zip.ZipOutputStream

import ee.cone.c4actor._
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.HttpProtocol.{Header, HttpPublication}
import ee.cone.c4proto.Protocol
import okio.{Buffer, GzipSink}

//todo un-publish/check
class PublishApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp
  with InitLocalsApp with ParallelObserversApp
{
  private lazy val publishDir = config.get("C4PUBLISH_DIR")
  private lazy val publishing = new Publishing(qMessages,qReducer,publishDir)
  override def toStart: List[Executable] = publishing :: super.toStart
  override def protocols: List[Protocol] = HttpProtocol :: super.protocols
}

class Publishing(qMessages: QMessages, reducer: Reducer, fromDir: String) extends Executable {
  def run(ctx: ExecutionContext): Unit = {
    val fromPath = Paths.get(fromDir)
    val visitor = new PublishFileVisitor(qMessages,reducer,fromPath)
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    ctx.complete(None)
  }
}

class PublishFileVisitor(qMessages: QMessages, reducer: Reducer, fromPath: Path) extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val path = s"/${fromPath.relativize(file)}"
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) None else Option(path.substring(pointPos+1))
    val contentType = ext.collect{ //not finished on gate-server side
      case "html" ⇒ "text/html; charset=UTF-8"
      case "js" ⇒ "application/javascript"
      case "ico" ⇒ "image/x-icon"
    }
    val headers = Header("Content-Encoding", "gzip") ::
      contentType.map(Header("Content-Type",_)).toList
    val body = Files.readAllBytes(file)
    val sink = new Buffer
    val gzipSink = new GzipSink(sink)
    gzipSink.write(new Buffer().write(body), body.length)
    gzipSink.close()
    val byteString = sink.readByteString()
    val world = reducer.createWorld(Map())
    Option(Map():World)
      .map(reducer.createTx(world))
      .map(LEvent.add(LEvent.update(HttpPublication(path,headers,byteString))))
      .foreach(m⇒qMessages.send(m))
    println(s"$path (${byteString.size}) published")
    FileVisitResult.CONTINUE
  }
}