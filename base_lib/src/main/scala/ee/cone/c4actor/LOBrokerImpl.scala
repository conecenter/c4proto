package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

@c4("ServerCompApp") final class LOBrokerImpl(
  actorName: ActorName,
  s3: S3Manager,
  execution: Execution,
)(
  val bucket: String = s"${actorName.prefix}.txr",
  bucketCreated: Promise[Unit] = Promise(),
  header: RawHeader = RawHeader("c","s3"),
  backoffFull: List[Long] = List(0L,50L,200L,1000L,4000L)
) extends LOBroker with LazyLogging {
  def needBucket(rec: QRecord): Future[Unit] = rec.topic match {
    case InboxTopicName() =>
      if(bucketCreated.isCompleted) bucketCreated.future else {
        implicit val ec: ExecutionContext = execution.mainExecutionContext
        for(_ <- s3.touch(bucket)) yield execution.success(bucketCreated,())
      }
  }

  def put(rec: QRecord): QRecord = {
    implicit val ec: ExecutionContext = execution.mainExecutionContext
    val res = for {
      _ <- needBucket(rec)
      path = s"${bucket}/${UUID.randomUUID()}"
      data =
        (path :: rec.headers.toList.flatMap(h=>List(h.key,h.value))).mkString(":")
      _ <- s3.put(path,rec.value)
    } yield new RefQRecord(rec.topic, data.getBytes(UTF_8), List(header))
    Await.result(res,Duration.Inf)
  }

  def isLocal(ev: RawEvent): Boolean = !ev.headers.contains(header)

  def get(events: List[RawEvent]): List[RawEvent] = get(events, backoffFull)

  @tailrec private def get(events: List[RawEvent], backoffLeft: List[Long]): List[RawEvent] =
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
          for(dataOpt <- s3.get(path))
            yield dataOpt.fold(ev)(data=>SimpleRawEvent(ev.srcId,ToByteString(data),headers))
      }),Duration.Inf),backoffLeft.tail)
    }
}

class RefQRecord(val topic: TopicName, val value: Array[Byte], val headers: Seq[RawHeader]) extends QRecord