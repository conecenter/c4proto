
package ee.cone.c4actor

import java.time.Instant

import com.squareup.wire.ProtoAdapter

import scala.collection.immutable.{Map, Queue, Seq}
import ee.cone.c4proto.{HasId, Id, Protocol, protocol}
import ee.cone.c4assemble.Types._
import ee.cone.c4assemble._
import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.{SrcId, TransientMap}

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
  @Id(0x0016) case class Firstborn(
    @Id(0x0011) srcId: String //dummy
    //@Id(0x0017) value: Long
  )
  /*@Id(0x0018) case class Leader(
    @Id(0x0019) actorName: String,
    @Id(0x001A) incarnationId: String
  )*/
}

//case class Task(srcId: SrcId, value: Product, offset: Long)

sealed trait TopicName
case class InboxTopicName() extends TopicName
case class LogTopicName() extends TopicName

trait QRecord {
  def topic: TopicName
  def value: Array[Byte]
}

trait RawQSender {
  def send(rec: List[QRecord]): List[Long]
}

case object OffsetWorldKey extends TransientLens[java.lang.Long](0L)

trait QMessages {
  def send[M<:Product](local: Context): Context
}

trait ToUpdate {
  def toUpdate[M<:Product](message: LEvent[M]): Update
}

object Types {
  type SrcId = String
  type TransientMap = Map[TransientLens[_],Object]
}

class Context(
  val injected: Map[SharedComponentKey[_],Object],
  val assembled: ReadModel,
  val transient: TransientMap
)

object ByPK {
  def apply[V<:Product](cl: Class[V]): ByPrimaryKeyGetter[V] =
    ByPrimaryKeyGetter(cl.getName)
}
//todo? def t[T[U],U](clO: Class[T[U]], cl1: Class[U]): Option[T[U]] = None

case class ByPrimaryKeyGetter[V<:Product](className: String)
  extends Getter[Context,Map[SrcId,V]]
{
  def of: Context ⇒ Map[SrcId, V] = context ⇒
    GetOrigIndexKey.of(context)(context,className).asInstanceOf[Map[SrcId,V]]
}

case object GetOrigIndexKey extends SharedComponentKey[(Context,String)⇒Map[SrcId,Product]]

trait Lens[C,I] extends Getter[C,I] {
  def modify: (I⇒I) ⇒ C⇒C
  def set: I ⇒ C⇒C
}

abstract class AbstractLens[C,I] extends Lens[C,I] {
  def modify: (I⇒I) ⇒ C⇒C = f ⇒ c ⇒ set(f(of(c)))(c)
}

abstract class TransientLens[Item](default: Item) extends AbstractLens[Context,Item] with Product {
  def of: Context ⇒ Item = context ⇒ context.transient.getOrElse(this, default).asInstanceOf[Item]
  def set: Item ⇒ Context ⇒ Context = value ⇒ context ⇒ new Context(
    context.injected,
    context.assembled,
    context.transient + (this → value.asInstanceOf[Object])
  )
}

case class LEvent[+M<:Product](srcId: SrcId, className: String, value: Option[M])
object LEvent {
  def update[M<:Product](value: M): Seq[LEvent[M]] =
    List(LEvent(ToPrimaryKey(value), value.getClass.getName, Option(value)))
  def delete[M<:Product](value: M): Seq[LEvent[M]] =
    List(LEvent(ToPrimaryKey(value), value.getClass.getName, None))
}

object WithPK {
  def apply[P<:Product](p: P): (SrcId,P) = ToPrimaryKey(p) → p
}

object TxAdd {
  def apply[M<:Product](out: Seq[LEvent[M]]): Context⇒Context = context ⇒
    WriteModelDebugAddKey.of(context)(out)(context)
}

trait Observer {
  def activate(world: Context): Seq[Observer]
}

trait TxTransform extends Product {
  def transform(local: Context): Context
}

case object WriteModelKey extends TransientLens[Queue[Update]](Queue.empty)
case object WriteModelDebugKey extends TransientLens[Queue[LEvent[Product]]](Queue.empty)
case object ReadModelAddKey extends SharedComponentKey[Seq[Update]⇒Context⇒Context]
case object WriteModelDebugAddKey extends SharedComponentKey[Seq[LEvent[Product]]⇒Context⇒Context]
case object WriteModelAddKey extends SharedComponentKey[Seq[Update]⇒Context⇒Context]

case object QAdapterRegistryKey extends SharedComponentKey[QAdapterRegistry]

class QAdapterRegistry(
  val byName: Map[String,ProtoAdapter[Product] with HasId],
  val byId: Map[Long,ProtoAdapter[Product] with HasId],
  val updatesAdapter: ProtoAdapter[QProtocol.Updates]
)

class RawEvent(val data: Array[Byte], val offset: Long)

trait RawWorld {
  def offset: Long
  def reduce(events: List[RawEvent]): RawWorld
  def hasErrors: Boolean
}

trait RawWorldFactory {
  def create(): RawWorld
}

trait RawObserver {
  def activate(rawWorld: RawWorld): RawObserver
}

trait ProgressObserverFactory {
  def create(endOffset: Long): RawObserver
}

trait RawSnapshot {
  def save(data: Array[Byte], offset: Long): Unit
  def loadRecent(): RawWorld
}

case object ErrorKey extends TransientLens[List[Exception]](Nil)
case object SleepUntilKey extends TransientLens[Instant](Instant.MIN)

object CheckedMap {
  def apply[K,V](pairs: Seq[(K,V)]): Map[K,V] =
    pairs.groupBy(_._1).transform((k,l)⇒Single(l)._2)
}

trait SnapshotConfig {
  def ignore: Set[Long]
}
