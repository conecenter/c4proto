
package ee.cone.c4gate_server
import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import okio.ByteString
import ee.cone.c4proto.ToByteString
import ee.cone.c4di._
import ee.cone.c4actor._
import ee.cone.c4actor.Types._

@c4("FileConsumerApp") final class FileConsuming(factory: FileConsumerFactory) extends Consuming {
  def process[R](from: NextOffset, body: Consumer=>R): R =
    body(factory.create(from, Files.readAllLines(TopicDir().resolve("snapshot_tx_list")).iterator()))
  def process[R](from: List[(TxLogName,NextOffset)], body: Consumer=>R): R = ???
}

@c4multi("FileConsumerApp") final class FileConsumer(from: NextOffset, it: java.util.Iterator[String])(loader: SnapshotLoader) extends Consumer {
  def poll(): List[ExtendedRawEvent] =
    if(it.hasNext) loader.load(RawSnapshot(it.next())).filter(_.srcId >= from).toList.map(FileRawEvent(_))
    else {
      Thread.sleep(Long.MaxValue)
      ???
    }
  def endOffset: NextOffset = ???
  def beginningOffset: NextOffset = ???
}

case class FileRawEvent(inner: RawEvent) extends ExtendedRawEvent {
  def txLogName: TxLogName = ???
  def mTime: Long = ???
  def srcId: SrcId = inner.srcId
  def data: ByteString = inner.data
  def headers: List[RawHeader] = inner.headers
  def withContent(headers: List[RawHeader], data: ByteString): ExtendedRawEvent = ???
}

@c4("FileConsumerApp") final class FileRawSnapshotLoader extends RawSnapshotLoader {
  def load(snapshot: RawSnapshot): ByteString =
    ToByteString(Files.readAllBytes(TopicDir().resolve(snapshot.relativePath)))
}

@c4("FileConsumerApp") final class FileSnapshotMaker extends SnapshotMaker {
  def make(task: SnapshotTask): List[RawSnapshot] =
    List(RawSnapshot(new String(Files.readAllBytes(TopicDir().resolve("snapshot_name")), UTF_8)))
}

//@provide def observers: Seq[TxObserver] = Seq(new TxObserver(new WorldCheckerObserver))




@c4app class FileConsumerAppBase extends VMExecutionApp with ExecutableApp with BaseApp //with ProtoApp
  with ServerCompApp with EnvConfigCompApp with NoAssembleProfilerCompApp
//with SnapshotUtilImplApp with EnvConfigCompApp