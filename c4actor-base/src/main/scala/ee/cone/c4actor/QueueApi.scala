
package ee.cone.c4actor

import ee.cone.c4actor.Types.{Index, SrcId, World}
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(@Id(0x0011) srcId: String, @Id(0x0012) valueTypeId: Long)
}

case class ActorName(value: String)

sealed trait TopicName
case class InboxTopicName(actorName: ActorName) extends TopicName
case class StateTopicName(actorName: ActorName) extends TopicName

trait QRecord {
  def topic: TopicName
  def key: Array[Byte]
  def value: Array[Byte]
}

trait RawQSender {
  def send(rec: QRecord): Unit
}

trait MessageHandlersApp {
  def messageHandlers: List[MessageHandler[_]] = Nil
}

trait QMessageMapper {
  def mapMessage(res: MessageMapping, rec: QRecord): MessageMapping
}

trait QMessages {
  def toRecord[M<:Product](message: LEvent[M]): QRecord
  def toTree(records: Iterable[QRecord]): Map[WorldKey[_],Index[Object,Object]]
  def send[M<:Product](message: LEvent[M]): Unit
}

case class LEvent[M<:Product](to: TopicName, srcId: SrcId, className: String, value: Option[M])
object LEvent {
  def update[M<:Product](to: ActorName, srcId: SrcId, value: M): LEvent[M] =
    LEvent(InboxTopicName(to), srcId, value.getClass.getName,  Option(value))
  def delete[M<:Product](to: ActorName, srcId: SrcId, cl: Class[M]): LEvent[M] =
    LEvent(InboxTopicName(to), srcId, cl.getName,  None)
}

abstract class MessageHandler[M<:Product](val mClass: Class[M]) {
  def handleMessage(message: M): Unit
}

trait MessageMapping {
  def world: World
  def add[M<:Product](out: LEvent[M]*): MessageMapping
  def toSend: Seq[QRecord]
  def actorName: ActorName
}

trait ActorFactory[R] {
  def create(actorName: ActorName, messageHandlers: List[MessageHandler[_]]): R
}

trait QMessageMapperFactory {
  def create(messageHandlers: List[MessageHandler[_]]): QMessageMapper
}
