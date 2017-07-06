
package ee.cone.c4actor

import com.squareup.wire.ProtoAdapter

import scala.collection.immutable.{Map, Seq}
import ee.cone.c4proto.{HasId, Id, Protocol, protocol}
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble.{JoinKey, WorldKey}
import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId

@protocol object QProtocol extends Protocol {
  /*@Id(0x0010) case class TopicKey(
      @Id(0x0011) srcId: String,
      @Id(0x0012) valueTypeId: Long
  )*/
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
  /*@Id(0x0018) case class Leader(
    @Id(0x0019) actorName: String,
    @Id(0x001A) incarnationId: String
  )*/
}

//case class Task(srcId: SrcId, value: Product, offset: Long)

case class ActorName(value: String)

sealed trait TopicName
case object NoTopicName extends TopicName
case class InboxTopicName() extends TopicName
case class LogTopicName() extends TopicName

trait QRecord {
  def topic: TopicName
  def value: Array[Byte]
}

trait RawQSender {
  def send(rec: List[QRecord]): List[Long]
}

case object OffsetWorldKey extends WorldKey[java.lang.Long](0L)

trait QMessages {
  def toUpdate[M<:Product](message: LEvent[M]): Update
  def offsetUpdate(value: Long): List[Update]
  def toUpdates(data: Array[Byte]): List[Update]
  def toTree(updates: Iterable[Update]): Map[WorldKey[Index[SrcId,Product]], Index[SrcId,Product]]
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

case class LEvent[+M<:Product](srcId: SrcId, className: String, value: Option[M])
object LEvent {
  def update[M<:Product](value: M): Seq[LEvent[M]] =
    List(LEvent(value.productElement(0).toString, value.getClass.getName, Option(value)))
  def delete[M<:Product](value: M): Seq[LEvent[M]] =
    List(LEvent(value.productElement(0).toString, value.getClass.getName, None))
  def add[M<:Product](out: Seq[LEvent[M]]): World⇒World =
    TxKey.modify(_.add(out))
}

trait WorldTx {
  def world: World
  def add[M<:Product](out: Seq[LEvent[M]]): WorldTx
  def add(out: List[Update]): WorldTx
  def toSend: Seq[Update]
  def toDebug: Seq[LEvent[Product]]
}

trait Observer {
  def activate(ctx: ObserverContext): Seq[Observer]
}

class ObserverContext(val getWorld: ()⇒World)

trait TxTransform extends Product {
  def transform(local: World): World
}

object NoWorldTx extends WorldTx {
  def world: World = Map.empty
  def add[M<:Product](out: Seq[LEvent[M]]): WorldTx = throw new Exception
  def add(out: List[Update]): WorldTx = throw new Exception
  def toSend: Seq[Update] = Nil
  def toDebug: Seq[LEvent[Product]] = Nil
}
case object TxKey extends WorldKey[WorldTx](NoWorldTx)

case object QAdapterRegistryKey extends WorldKey[()⇒QAdapterRegistry](()⇒throw new Exception)

class QAdapterRegistry(
  val byName: Map[String,ProtoAdapter[Product] with HasId],
  val byId: Map[Long,ProtoAdapter[Product] with HasId],
  val updatesAdapter: ProtoAdapter[QProtocol.Updates]
)

trait RawObserver {
  def offset: Long
  def reduce(data: Array[Byte], offset: Long): RawObserver
  def hasErrors: Boolean
  def activate(fresh: ()⇒RawObserver, endOffset: Long): RawObserver
  def isActive: Boolean
}

trait RawSnapshot {
  def save(data: Array[Byte], offset: Long): Unit
  def loadRecent: RawObserver ⇒ RawObserver
}
