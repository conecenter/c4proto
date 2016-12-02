package ee.cone.c4proto

import java.util.concurrent.ExecutorService

import ee.cone.c4proto.Types.{SrcId, World}

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
  def mapMessage(command: M): Seq[QProducerRecord]
}

trait QStatePartReceiver {
  def reduce(world: World, records: Iterable[QConsumerRecord]): World
}

trait QMessageMapper {
  def streamKeys: List[StreamKey]
  def mapMessage(rec: QConsumerRecord): Seq[QProducerRecord]
}

trait QMessages {
  def update[M](srcId: SrcId, value: M): QProducerRecord
  def delete[M](srcId: SrcId, cl: Class[M]): QProducerRecord
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

trait WorldProvider {
  def world: World
}