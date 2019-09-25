
package ee.cone.c4actor

import java.time.Instant

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.MetaAttrProtocol.D_TxTransformNameMeta
import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4actor.Types._
import ee.cone.c4assemble._
import ee.cone.c4proto._
import okio.ByteString

import scala.collection.immutable.{Map, Queue, Seq}
import scala.concurrent.{ExecutionContext, Future}

@protocol("ProtoAutoApp") object QProtocolBase   {

  /*@Id(0x0010) case class TopicKey(
      @Id(0x0011) srcId: String,
      @Id(0x0012) valueTypeId: Long
  )*/

  /**
    * Central update class
    * @param srcId == ToPrimaryKey(orig)
    * @param valueTypeId == QAdapterRegistry.byName(orig.getClass.getName).id
    * @param value == QAdapterRegistry.byId(valueTypeId).encode(orig)
    * @param flags == One of {0L, 1L, 2L, 4L}
    **/
  case class N_Update(
    @Id(0x0011) srcId: SrcId,
    @Id(0x0012) valueTypeId: Long,
    @Id(0x0013) value: okio.ByteString,
    @Id(0x001C) flags: Long
  )

  @Id(0x0014) case class S_Updates(
    @Id(0x0011) srcId: SrcId, //dummy
    @Id(0x0015) updates: List[N_Update]
  )

    @Id(0x0016) case class S_Firstborn(
    @Id(0x0011) srcId: SrcId, //app class
    @Id(0x001A) txId: String
  )

  @Id(0x0017) case class S_FailedUpdates(
    @Id(0x0011) srcId: SrcId,
    @Id(0x0018) reason: String
  )

  @Id(0x0019) case class N_TxRef( //add actorName if need cross ms mortality?
    @Id(0x0011) srcId: SrcId,
    @Id(0x001A) txId: String
  )

  @Id(0x001B) case class S_Offset(
    @Id(0x0011) srcId: SrcId, //app class
    @Id(0x001A) txId: String
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
  def headers: Seq[RawHeader]
}

trait RawQSender {
  def send(rec: List[QRecord]): List[NextOffset]
}

object OffsetHexSize{ def apply() = 16 }
case object ReadAfterWriteOffsetKey extends TransientLens[NextOffset]("0" * OffsetHexSize())

trait QMessages {
  def send[M<:Product](local: Context): Context
  // pair of worldProvider.createTx/send can be turned to tx{local=>...} or
  // worldProvider can be not in App, but passed to richServer.init(worldProvider),
  // where richServers wrapped with txTr with AtomicRef;
  // HOWEVER READ-AFTER-WRITE problem here is harder
}

class UpdateCompressionMinSize(val value: Long)

trait ToUpdate {
  def toUpdate[M<:Product](message: LEvent[M]): N_Update
  def toBytes(updates: List[N_Update]): (Array[Byte], List[RawHeader])
  /**
    * Transforms RawEvents to updates, adds TxId and removes ALL flags
    *
    * @param events events from Kafka or Snapshot
    * @return updates
    */
  def toUpdates(events: List[RawEvent]): List[N_Update]
  /**
    * Transforms RawEvents to updates, adds TxId and keeps ALL but TxId flags
    *
    * @param events events from Kafka or Snapshot
    * @return updates
    */
  def toUpdatesWithFlags(events: List[RawEvent]): List[N_Update]
  def toKey(up: N_Update): N_Update
  def by(up: N_Update): (TypeId, SrcId)
}

object Types {
  type ClName = String
  type TypeId = Long
  type SrcId = String
  def SrcIdProtoAdapter: ProtoAdapter[SrcId] = com.squareup.wire.ProtoAdapter.STRING
  def SrcIdEmpty = ""
  type TransientMap = Map[TransientLens[_],Object]
  type SharedComponentMap = Map[SharedComponentKey[_],Object]
  type NextOffset = String
}

trait SharedContext {
  def injected: SharedComponentMap
}

trait AssembledContext {
  def assembled: ReadModel
  def executionContext: OuterExecutionContext
}

trait OffsetContext {
  def offset: NextOffset
}

trait RichContext extends OffsetContext with SharedContext with AssembledContext

class Context(
  val injected: SharedComponentMap,
  val assembled: ReadModel,
  val executionContext: OuterExecutionContext,
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
case object GetAssembleOptions extends SharedComponentKey[ReadModel⇒AssembleOptions]

trait Lens[C,I] extends Getter[C,I] {
  def modify: (I⇒I) ⇒ C⇒C
  def set: I ⇒ C⇒C
}

abstract class AbstractLens[C,I] extends Lens[C,I] {
  def modify: (I⇒I) ⇒ C⇒C = f ⇒ c ⇒ set(f(of(c)))(c)
}

abstract class TransientLens[Item](val default: Item) extends AbstractLens[Context,Item] with Product {
  def of: Context ⇒ Item = context ⇒ context.transient.getOrElse(this, default).asInstanceOf[Item]
  def set: Item ⇒ Context ⇒ Context = value ⇒ context ⇒ new Context(
    context.injected,
    context.assembled,
    context.executionContext,
    context.transient + (this → value.asInstanceOf[Object])
  )
}

trait LEvent[+M <: Product] extends Product {
  def srcId: SrcId
  def className: String
}

case class UpdateLEvent[+M <: Product](srcId: SrcId, className: String, value: M) extends LEvent[M]

case class DeleteLEvent[+M <: Product](srcId: SrcId, className: String) extends LEvent[M]

object LEvent {
  def update(models: Seq[Product]): Seq[LEvent[Product]] =
    models.flatMap(update)
  def update(model: Product): Seq[LEvent[Product]] =
    List(UpdateLEvent(ToPrimaryKey(model), model.getClass.getName, model))
  def delete(models: Seq[Product]): Seq[LEvent[Product]] =
    models.flatMap(delete)
  def delete(model: Product): Seq[LEvent[Product]] =
    List(DeleteLEvent(ToPrimaryKey(model), model.getClass.getName))
}

object WithPK {
  def apply[P<:Product](p: P): (SrcId,P) = ToPrimaryKey(p) → p
}

object TxAdd {
  def apply[M<:Product](out: Seq[LEvent[M]]): Context⇒Context = context ⇒
    WriteModelDebugAddKey.of(context)(out)(context)
}

trait Observer[Message] {
  def activate(world: Message): Seq[Observer[Message]]
}

case object TxTransformOrigMeta{
  def apply(name: String): Context ⇒ Context = TxTransformOrigMetaKey.set(MetaAttr(D_TxTransformNameMeta(name)) :: Nil)
}
case object TxTransformOrigMetaKey extends TransientLens[List[MetaAttr]](Nil)

trait TxTransform extends Product {
  def transform(local: Context): Context
}

case object WriteModelKey extends TransientLens[Queue[N_Update]](Queue.empty)
case object WriteModelDebugKey extends TransientLens[Queue[LEvent[Product]]](Queue.empty)
case object ReadModelAddKey extends SharedComponentKey[Seq[RawEvent]⇒(SharedContext with AssembledContext)⇒ReadModel]
case object WriteModelDebugAddKey extends SharedComponentKey[Seq[LEvent[Product]]⇒Context⇒Context]
case object WriteModelAddKey extends SharedComponentKey[Seq[N_Update]⇒Context⇒Context]

case class RawHeader(key: String, value: String)

trait RawEvent extends Product {
  def srcId: SrcId
  def data: ByteString
  def headers: List[RawHeader]
}
case class SimpleRawEvent(srcId: SrcId, data: ByteString, headers: List[RawHeader]) extends RawEvent

trait RichRawWorldReducer {
  def reduce(context: Option[SharedContext with AssembledContext], events: List[RawEvent]): RichContext
}

trait FinishedRawObserver extends RawObserver
trait RawObserver {
  def activate(rawWorld: RichContext): RawObserver
}

trait ProgressObserverFactory {
  def create(endOffset: NextOffset): RawObserver
}



trait MTime {
  def mTime: Long
}

//trait RawDebugOptions {
//  def load(key: String): Array[Byte]
//  def save(key: String, value: Array[Byte]): Unit
//}

// problem with ErrorKey is that when we check it world is different
case object ErrorKey extends TransientLens[List[Exception]](Nil)
case object SleepUntilKey extends TransientLens[Instant](Instant.MIN)

object CheckedMap {
  def apply[K,V](pairs: Seq[(K,V)]): Map[K,V] =
    pairs.groupBy(_._1).transform((k,l)⇒Single(l)._2)
}

trait AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]): JoiningProfiling
  def addMeta(transition: WorldTransition, updates: Seq[N_Update]): Future[Seq[N_Update]]
}

case object DebugStateKey extends TransientLens[Option[(RichContext,RawEvent)]](None)

trait UpdatesProcessorsApp {
  def processors: List[UpdatesPreprocessor] = Nil
}

trait UpdatesPreprocessor {
  /**
    * Ability to add extra updates on some events
    * @param updates current events
    * @return extra updates to add to total list
    */
  def process(updates: Seq[N_Update]): Seq[N_Update]
}

trait KeyFactory {
  def rawKey(className: String): AssembledKey
}

trait UpdateProcessor {
  def process(updates: Seq[N_Update], prevQueueSize: Int): Seq[N_Update]
}