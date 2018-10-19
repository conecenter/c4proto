
package ee.cone.c4actor

import java.time.Instant

import com.squareup.wire.ProtoAdapter

import scala.collection.immutable.{Map, Queue, Seq}
import ee.cone.c4proto.{HasId, Id, Protocol, protocol}
import ee.cone.c4assemble.Types._
import ee.cone.c4assemble._
import ee.cone.c4actor.QProtocol.{Update, Updates}
import ee.cone.c4actor.Types.{NextOffset, SharedComponentMap, SrcId, TransientMap}
import okio.ByteString

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
  )

  @Id(0x0017) case class FailedUpdates(
    @Id(0x0011) srcId: String,
    @Id(0x0018) reason: String
  )

  @Id(0x0019) case class DebugTx(
    @Id(0x0011) srcId: String,
    @Id(0x0015) updates: List[Update]
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
  def send(rec: List[QRecord]): List[NextOffset]
}

object OffsetHexSize{ def apply() = 16 }
case object OffsetWorldKey extends TransientLens[NextOffset]("0" * OffsetHexSize())

trait QMessages {
  def send[M<:Product](local: Context): Context
}

trait ToUpdate {
  def toUpdate[M<:Product](message: LEvent[M]): Update
  def toBytes(updates: List[Update]): Array[Byte]
  def toUpdates(data: ByteString): List[Update]
}

object Types {
  type SrcId = String
  type TransientMap = Map[TransientLens[_],Object]
  type SharedComponentMap = Map[SharedComponentKey[_],Object]
  type NextOffset = String
}

trait SharedContext {
  def injected: SharedComponentMap
}

trait AssembledContext {
  def assembled: ReadModel
}

trait RichContext extends SharedContext with AssembledContext {
  def offset: NextOffset
}

class Context(
  val injected: SharedComponentMap,
  val assembled: ReadModel,
  val transient: TransientMap
) extends SharedContext with AssembledContext

object ByPK {
  def apply[V<:Product](cl: Class[V]): ByPrimaryKeyGetter[V] =
    ByPrimaryKeyGetter(cl.getName)
}
//todo? def t[T[U],U](clO: Class[T[U]], cl1: Class[U]): Option[T[U]] = None

case class ByPrimaryKeyGetter[V<:Product](className: String)
  extends Getter[SharedContext with AssembledContext,Map[SrcId,V]]
{
  def of: SharedContext with AssembledContext ⇒ Map[SrcId, V] = context ⇒
    GetOrigIndexKey.of(context)(context,className).asInstanceOf[Map[SrcId,V]]
}

case object GetOrigIndexKey extends SharedComponentKey[(AssembledContext,String)⇒Map[SrcId,Product]]

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
  def activate(world: RichContext): Seq[Observer]
}

trait TxTransform extends Product {
  def transform(local: Context): Context
}

case object WriteModelKey extends TransientLens[Queue[Update]](Queue.empty)
case object WriteModelDebugKey extends TransientLens[Queue[LEvent[Product]]](Queue.empty)
case object ReadModelAddKey extends SharedComponentKey[SharedContext⇒Seq[RawEvent]⇒ReadModel⇒ReadModel]
case object WriteModelDebugAddKey extends SharedComponentKey[Seq[LEvent[Product]]⇒Context⇒Context]
case object WriteModelAddKey extends SharedComponentKey[Seq[Update]⇒Context⇒Context]

case object QAdapterRegistryKey extends SharedComponentKey[QAdapterRegistry]

class QAdapterRegistry(
  val byName: Map[String,ProtoAdapter[Product] with HasId],
  val byId: Map[Long,ProtoAdapter[Product] with HasId],
  val updatesAdapter: ProtoAdapter[QProtocol.Updates] with HasId
)

case class RawEvent(srcId: SrcId, data: ByteString)
case class ClearUpdates(updates: Updates)
case class KeepUpdates(srcId: SrcId)

trait RawWorld {
  def offset: NextOffset
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
  def create(endOffset: NextOffset): RawObserver
}

trait SnapshotSaver {
  def save(offset: NextOffset, data: Array[Byte]): Unit
}
class Snapshot(val offset: NextOffset, val uuid: String, val raw: RawSnapshot)
trait SnapshotLoader {
  def list: List[Snapshot]
}
trait RawSnapshotSaver {
  def save(name: String, data: Array[Byte]): Unit
}
trait RawSnapshotLoader {
  def list: List[RawSnapshot]
}
trait RawSnapshot {
  def name: String
  def load(): ByteString
}
trait Removable {
  def remove(): Unit
}
trait SnapshotTime {
  def mTime: Long
}

//trait RawDebugOptions {
//  def load(key: String): Array[Byte]
//  def save(key: String, value: Array[Byte]): Unit
//}

case object ErrorKey extends TransientLens[List[Exception]](Nil)
case object SleepUntilKey extends TransientLens[Instant](Instant.MIN)

object CheckedMap {
  def apply[K,V](pairs: Seq[(K,V)]): Map[K,V] =
    pairs.groupBy(_._1).transform((k,l)⇒Single(l)._2)
}

trait SnapshotConfig {
  def ignore: Set[Long]
}

trait AssembleProfiler {
  def createSerialJoiningProfiling(localOpt: Option[Context]): SerialJoiningProfiling
  def addMeta(profiling: SerialJoiningProfiling, updates: Seq[Update]): Seq[Update]
}

case object DebugKey extends SharedComponentKey[Option[ReadModel]]