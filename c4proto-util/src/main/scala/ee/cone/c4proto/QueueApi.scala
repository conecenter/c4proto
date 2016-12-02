package ee.cone.c4proto

import java.util.concurrent.ExecutorService

import ee.cone.c4proto.Types.{SrcId, World}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

trait QConsumerRecord {
  def topic: TopicName
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Long
}

class QProducerRecord(val topic: TopicName, val key: Array[Byte], val value: Array[Byte])

case class TopicName(value: String)

trait RawQSender {
  def send(rec: QProducerRecord): Unit
}

abstract class MessageMapper[M](val topic: TopicName, val mClass: Class[M]) {
  def mapMessage(command: M): Seq[QProducerRecord]
}

trait QStatePartReceiver {
  def receiveStateParts(records: Iterable[QConsumerRecord]): Unit
}
trait QMessageMapper {
  def mapMessage(rec: QConsumerRecord): Seq[QProducerRecord]
}

trait QMessages {
  def update[M](topic: TopicName, srcId: SrcId, value: M): QProducerRecord
  def delete[M](topic: TopicName, srcId: SrcId, cl: Class[M]): QProducerRecord
}

////

trait ToStartApp {
  def toStart: List[CanStart] = Nil
}

trait CanStart {
  def start(): Unit
}

trait Pool {
  def make(): ExecutorService
}