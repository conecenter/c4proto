
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

trait MessageMappersApp {
  def messageMappers: List[MessageMapper[_]] = Nil
}

trait QMessageMapper {
  def mapMessage(world: World, rec: QRecord): Seq[QRecord]
}

trait QMessages {
  def toRecord(actorName: Option[ActorName], message: MessageMapResult): QRecord
  def toTree(records: Iterable[QRecord]): Map[WorldKey[_],Index[Object,Object]]
  def send[M<:Product](message: Send[M]): Unit
}

sealed trait MessageMapResult
case class Update[M<:Product](srcId: SrcId, value: M) extends MessageMapResult
case class Delete[M<:Product](srcId: SrcId, value: Class[M]) extends MessageMapResult
case class Send[M<:Product](actorName: ActorName, value: M) extends MessageMapResult

abstract class MessageMapper[M](val mClass: Class[M]) {
  def mapMessage(world: World, message: M): Seq[MessageMapResult]
}

trait ActorFactory[R] {
  def create(actorName: ActorName, messageMappers: List[MessageMapper[_]]): R
}