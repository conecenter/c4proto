package ee.cone.c4proto

import java.util.concurrent.ExecutorService

import ee.cone.c4proto.Types.{Index, SrcId, World}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

trait QConsumerRecord {
  def streamKey: StreamKey
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Long
}

class QProducerRecord(val key: Array[Byte], val value: Array[Byte])

case class StreamKey(from: String, to: String)

trait RawQSender {
  def send(streamKey: StreamKey, rec: QProducerRecord): Unit
}

abstract class MessageMapper[M](val streamKey: StreamKey, val mClass: Class[M]) {
  def mapMessage(message: M): Seq[QProducerRecord]
}

trait MessageMappersApp {
  def messageMappers: List[MessageMapper[_]] = Nil
}

trait QMessageMapper {
  def streamKeys: List[StreamKey]
  def mapMessage(rec: QConsumerRecord): Seq[QProducerRecord]
}

trait QMessages {
  def update[M](srcId: SrcId, value: M): QProducerRecord
  def delete[M](srcId: SrcId, cl: Class[M]): QProducerRecord
  def toTree(records: Iterable[QConsumerRecord]): Map[WorldKey[_],Index[Object,Object]]
}

case class QMessage(srcId: SrcId, className: String, value: Option[Product])
object QMessage {
  def apply(value: Product): QMessage =
    QMessage("", value.getClass.getName, Option(value))
  def apply(srcId: SrcId, value: Product): QMessage =
    QMessage(srcId, value.getClass.getName, Option(value))
  def apply(srcId: SrcId, cl: Class[_]): QMessage =
    QMessage(srcId, cl.getName, None)
}

////

trait ToStartApp {
  def toStart: List[CanStart] = Nil
}

trait ShouldStartEarly {
  def isReady: Boolean
}

trait CanStart {
  def start(pool: ExecutorService): Unit
  def early: Option[ShouldStartEarly]
}

trait CanFail {
  def isDone: Boolean
}

trait ServerFactory {
  def toServer(runnable: Runnable): CanStart
}

trait WorldProvider {
  def world: World
}

////

object OnShutdown {
  def apply(f: ()⇒Unit): Unit = Runtime.getRuntime.addShutdownHook(new Thread(){
    override def run(): Unit = f()
  })
}