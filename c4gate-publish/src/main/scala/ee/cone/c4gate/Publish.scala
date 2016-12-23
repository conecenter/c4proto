package ee.cone.c4gate

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import ee.cone.c4actor._
import ee.cone.c4gate.InternetProtocol.{Header, HttpPublication}
import ee.cone.c4proto.Protocol

object Publish extends Main(new PublishApp().execution.run)

//todo un-publish/check
class PublishApp extends ServerApp
  with EnvConfigApp
  with QMessagesApp
  with QReducerApp
  with TreeAssemblerApp
  with KafkaProducerApp
{
  private lazy val publishDir = config.get("C4PUBLISH_DIR")
  private lazy val publishing = new Publishing(qMessages,qReducer,publishDir)
  override def toStart: List[Executable] = publishing :: super.toStart
  override def protocols: List[Protocol] = InternetProtocol :: super.protocols
}

class Publishing(qMessages: QMessages, reducer: Reducer, fromDir: String) extends Executable {
  def run(ctx: ExecutionContext): Unit = {
    val fromPath = Paths.get(fromDir)
    val visitor = new PublishFileVisitor(qMessages,reducer,fromPath)
    val depth = Integer.MAX_VALUE
    val options = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(fromPath, options, depth, visitor)
    ctx.serving.complete(())
  }
}

class PublishFileVisitor(qMessages: QMessages, reducer: Reducer, fromPath: Path) extends SimpleFileVisitor[Path] {
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val tx = reducer.createTx(Map())
    val path = s"/${fromPath.relativize(file)}"
    val pointPos = path.lastIndexOf(".")
    val ext = if(pointPos<0) None else Option(path.substring(pointPos+1))
    val contentType = ext.collect{ //not finished on gate-server side
      case "html" ⇒ "text/html; charset=UTF-8"
      case "js" ⇒ "application/javascript"
      case "ico" ⇒ "image/x-icon"
    }
    val headers = contentType.map(Header("Content-Type",_)).toList
    val bytes = Files.readAllBytes(file)
    val byteString = okio.ByteString.of(bytes,0,bytes.length)
    qMessages.send(tx.add(LEvent.update(HttpPublication(path,headers,byteString))))
    println(s"$path published")
    FileVisitResult.CONTINUE
  }
}