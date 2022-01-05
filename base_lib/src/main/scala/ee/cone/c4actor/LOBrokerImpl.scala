package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import okio.ByteString

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

@c4("ServerCompApp") final class LOBrokerImpl(
  s3: S3Manager,
  execution: Execution,
)(
  header: RawHeader = RawHeader("c","s3"),
  backoffFull: List[Long] = List(0L,50L,200L,1000L,4000L),
  val bucketPostfix: String = "txr",
) extends LOBroker with LazyLogging {

  def put(rec: QRecord): QRecord = {
    val path = s"$bucketPostfix/${UUID.randomUUID()}"
    val data =
      (path :: rec.headers.toList.flatMap(h=>List(h.key,h.value))).mkString(":")
    s3.put(rec.topic,path,rec.value)
    new RefQRecord(rec.topic, data.getBytes(UTF_8), List(header))
  }

  def isLocal(ev: RawEvent): Boolean = !ev.headers.contains(header)

  def get(events: List[RawEvent with HasTxLogName]): List[RawEvent] =
    get(events, backoffFull)

  @tailrec private def get(events: List[RawEvent with HasTxLogName], backoffLeft: List[Long]): List[RawEvent] =
    if(events.forall(isLocal)) events else {
      backoffLeft match {
        case 0L :: _ => ()
        case p :: _ =>
          logger.debug(s"waiting for s3 $p ms")
          Thread.sleep(p)
        case Seq() => throw new Exception(events.filterNot(isLocal).toString)
      }
      implicit val ec: ExecutionContext = execution.mainExecutionContext
      get(Await.result(Future.sequence(events.map{
        case ev if isLocal(ev) => Future.successful(ev)
        case ev =>
          val path :: opt = ev.data.utf8().split(':').toList
          val headers =
            opt.grouped(2).map{ case k::v::Nil => RawHeader(k,v) }.toList
          val txLogName = ev.txLogName
          for(dataOpt <- s3.get(txLogName,path))
            yield dataOpt.fold(ev)(data=>RefRawEvent(ev.srcId,ToByteString(data),headers,txLogName))
      }),Duration.Inf),backoffLeft.tail)
    }
}

case class RefRawEvent(
  srcId: SrcId, data: ByteString, headers: List[RawHeader], txLogName: TxLogName
) extends RawEvent with HasTxLogName
class RefQRecord(
  val topic: TxLogName, val value: Array[Byte], val headers: Seq[RawHeader]
) extends QRecord