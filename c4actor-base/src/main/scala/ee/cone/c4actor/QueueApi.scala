
package ee.cone.c4actor

import scala.collection.immutable.Map
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble.{JoinKey, WorldKey}
import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId

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
  @Id(0x0016) case class Offset(
    @Id(0x0011) srcId: String, //dummy
    @Id(0x0017) value: Long
  )
  @Id(0x0018) case class Leader(
    @Id(0x0019) actorName: String,
    @Id(0x001A) incarnationId: String
  )
}

//case class Task(srcId: SrcId, value: Product, offset: Long)

case class ActorName(value: String)

sealed trait TopicName
case object NoTopicName extends TopicName
case class InboxTopicName() extends TopicName
case class StateTopicName(actorName: ActorName) extends TopicName
case class LogTopicName() extends TopicName

trait QRecord {
  def topic: TopicName
  def key: Array[Byte]
  def value: Array[Byte]
  def offset: Option[Long]
}

trait RawQSender {
  def send(rec: List[QRecord]): List[Long]
}

case object OffsetWorldKey extends WorldKey[java.lang.Long](0L)

trait QMessages {
  def toUpdate[M<:Product](message: LEvent[M]): Update
  def offsetUpdate(value: Long): List[Update]
  def toRecord(topicName: TopicName, update: Update): QRecord
  def toRecords(actorName: ActorName, rec: QRecord): List[QRecord]
  def toTree(records: Iterable[QRecord]): Map[WorldKey[Index[SrcId,Product]], Index[SrcId,Product]]
  def send[M<:Product](local: World): World
  def worldOffset: World ⇒ Long
}

object Types {
  type SrcId = String
}

object By {
  def srcId[V<:Product](cl: Class[V]): WorldKey[Index[SrcId,V]] = srcId[V](cl.getName)
  def srcId[V<:Product](className: String): WorldKey[Index[SrcId,V]] =
    JoinKey[SrcId,V]("SrcId", classOf[SrcId].getName, className)
}

case class LEvent[M<:Product](srcId: SrcId, className: String, value: Option[M])
object LEvent {
  def update[M<:Product](value: M): Seq[LEvent[M]] =
    Seq(LEvent(value.productElement(0).toString, value.getClass.getName, Option(value)))
  def delete[M<:Product](value: M): Seq[LEvent[M]] =
    Seq(LEvent(value.productElement(0).toString, value.getClass.getName, None))
  def add[M<:Product](out: Seq[LEvent[M]]): World⇒World =
    TxKey.modify(_.add(out))
}

trait WorldTx {
  def world: World
  def add[M<:Product](out: Iterable[LEvent[M]]): WorldTx
  def toSend: Seq[Update]
  def toDebug: Seq[LEvent[Product]]
}

trait Observer {
  def activate(ctx: ObserverContext): Seq[Observer]
}

class ObserverContext(val executionContext: ExecutionContext, val getWorld: ()⇒World)

trait TxTransform extends Product {
  def transform(local: World): World
}

object NoWorldTx extends WorldTx {
  def world: World = Map.empty
  def add[M <: Product](out: Iterable[LEvent[M]]): WorldTx = throw new Exception
  def toSend: Seq[Update] = Nil
  def toDebug: Seq[LEvent[Product]] = Nil
}
case object TxKey extends WorldKey[WorldTx](NoWorldTx)