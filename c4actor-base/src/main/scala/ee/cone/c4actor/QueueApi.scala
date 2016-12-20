
package ee.cone.c4actor

import ee.cone.c4actor.Types.{Index, SrcId, World}
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(
      @Id(0x0011) srcId: String,
      @Id(0x0012) valueTypeId: Long
  )
  @Id(0x0013) case class Task(
      @Id(0x0011) srcId: String,
      @Id(0x0012) valueTypeId: Long,
      @Id(0x0014) value: okio.ByteString,
      @Id(0x0015) offset: Long
  )
  @Id(0x0016) case class Commit(
      @Id(0x0018) update: List[Update]
  )
  case class Update(
      @Id(0x0011) srcId: String,
      @Id(0x0012) valueTypeId: Long,
      @Id(0x0017) oldValue: okio.ByteString,
      @Id(0x0014) value: okio.ByteString
  )
}

case class TopicName(value: String)

trait QRecord {
  def topic: TopicName
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Option[Long]
}

trait RawQSender {
  def send(rec: QRecord): Unit
}

trait MessageMappersApp {
  def messageMappers: List[MessageMapper[_]] = Nil
}

trait QMessageMapper {
  def mapMessage(res: MessageMapping, rec: QRecord): MessageMapping
}

trait QMessages {
  def toRecord[M<:Product](message: LEvent[M]): QRecord
  def toTree(records: Iterable[QRecord]): Map[WorldKey[_],Index[Object,Object]]
  def send[M<:Product](message: LEvent[M]): Unit
}

case class LEvent[M<:Product](
  to: TopicName,
  srcId: SrcId,
  className: String,
  value: Option[M],
  offset: Option[Long]
)
object LEvent {
  def update[M<:Product](to: TopicName, srcId: SrcId, value: M): LEvent[M] =
    LEvent(to, srcId, value.getClass.getName,  Option(value), None)
  def delete[M<:Product](to: TopicName, srcId: SrcId, cl: Class[M]): LEvent[M] =
    LEvent(to, srcId, cl.getName,  None, None)
}

abstract class MessageMapper[M<:Product](val mClass: Class[M]) {
  def mapMessage(res: MessageMapping, message: LEvent[M]): MessageMapping
}

trait MessageMapping {
  def world: World
  def add[M<:Product](out: LEvent[M]*): MessageMapping
  def toSend: Seq[QRecord]
  def topicName: TopicName
}

trait QMessageMapperFactory {
  def create(messageMappers: List[MessageMapper[_]]): QMessageMapper
}
