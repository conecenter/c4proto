package ee.cone.c4proto

import ee.cone.c4proto.Types.{SrcId, World}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

trait QRecord {
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Long
}

case class TopicName(value: String)

trait RawQSender {
  def send(topic: TopicName, key: Array[Byte], value: Array[Byte]): Unit
}

trait CommandReceiver[M] {
  def className: String
  def handle(world: World, command: M): World
}

trait QReceiver {
  def receiveEvents(world: World, records: Iterable[QRecord]): World
  def receiveCommand(world: World, rec: QRecord): World
}

trait QSender {
  def sendUpdate[M](topic: TopicName, srcId: SrcId, value: M): Unit
  def sendDelete[M](topic: TopicName, srcId: SrcId, cl: Class[M]): Unit
}
