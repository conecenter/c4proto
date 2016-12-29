
package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{Index, SrcId, World}
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object QProtocol extends Protocol {
  @Id(0x0010) case class TopicKey(
      @Id(0x0011) srcId: String,
      @Id(0x0012) valueTypeId: Long
  )
  case class Update(
    @Id(0x0011) srcId: String,
    @Id(0x0012) valueTypeId: Long,
    @Id(0x0013) value: okio.ByteString
  )
  @Id(0x0014) case class Updates(
      @Id(0x0011) srcId: String, //dummy
      @Id(0x0015) updates: List[Update]
  )
}

//case class Task(srcId: SrcId, value: Product, offset: Long)

case class ActorName(value: String)

sealed trait TopicName
case object NoTopicName extends TopicName
case class InboxTopicName() extends TopicName
case class StateTopicName(actorName: ActorName) extends TopicName

trait QRecord {
  def topic: TopicName
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Option[Long]
}

trait RawQSender {
  def send(rec: QRecord): Long
}

case object OffsetWorldKey extends WorldKey[java.lang.Long](0L)

trait QMessages {
  def toUpdate[M<:Product](message: LEvent[M]): Update
  def toRecord(topicName: TopicName, update: Update): QRecord
  def toRecords(actorName: ActorName, rec: QRecord): List[QRecord]
  def toTree(records: Iterable[QRecord]): Map[WorldKey[_],Index[Object,Object]]
  def send[M<:Product](local: World): World
}

case class LEvent[M<:Product](srcId: SrcId, className: String, value: Option[M])
object LEvent {
  def update[M<:Product](value: M): LEvent[M] =
    LEvent(value.productElement(0).toString, value.getClass.getName, Option(value))
  def delete[M<:Product](value: M): LEvent[M] =
    LEvent(value.productElement(0).toString, value.getClass.getName, None)
  def add[M<:Product](out: Iterable[LEvent[M]]): World⇒World =
    TxKey.transform(_.add(out))
}

trait WorldTx {
  def world: World
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx
  def toSend: Seq[Update]
}

trait Observer {
  def activate(getTx: ()⇒World): Seq[Observer]
}

trait TxTransform {
  def transform(local: World): World
}

object NoWorldTx extends WorldTx {
  def world: World = Map.empty
  def add[M <: Product](out: Iterable[LEvent[M]]): WorldTx = throw new Exception
  def toSend: Seq[Update] = Nil
}
case object TxKey extends WorldKey[WorldTx](NoWorldTx)