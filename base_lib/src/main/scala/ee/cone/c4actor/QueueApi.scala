
package ee.cone.c4actor

import java.time.Instant
import ee.cone.c4actor.MetaAttrProtocol.D_TxTransformNameMeta
import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4actor.Types._
import ee.cone.c4assemble._
import ee.cone.c4di.c4
import ee.cone.c4proto._
import okio.ByteString

import java.net.http.HttpClient
import scala.collection.immutable.{Map, Queue, Seq}
import scala.concurrent.{ExecutionContext, Future}

trait UpdateFlag {
  /**
    * Flag value must be pow(2, x), where x from 0 to 63, must be unique
    **/
  def flagValue: Long
}

@protocol("ProtoApp") object QProtocol   {

  /*@Id(0x0010) case class TopicKey(
      @Id(0x0011) srcId: String,
      @Id(0x0012) valueTypeId: Long
  )*/

  /**
    * Central update class
    * @param srcId == ToPrimaryKey(orig)
    * @param valueTypeId == QAdapterRegistry.byName(orig.getClass.getName).id
    * @param value == QAdapterRegistry.byId(valueTypeId).encode(orig)
    * @param flags == '|' of UpdateFlag.flagValue{1L, 2L, 4L, 8L}
    */
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
  case class N_CompressedUpdates(
    @Id(0x001D) compressorName: String,
    @Id(0x001E) values: List[ByteString]
  )

}

//case class Task(srcId: SrcId, value: Product, offset: Long)

trait TxLogName extends Product {
  def value: String
}
trait CurrentTxLogName extends TxLogName

trait QRecord {
  def topic: TxLogName
  def value: Array[Byte]
  def headers: Seq[RawHeader]
}

trait RawQSender {
  def send(rec: QRecord): NextOffset
}
trait RawQSenderExecutable extends Executable

object OffsetHexSize{ def apply() = 16 }
case object ReadAfterWriteOffsetKey extends TransientLens[NextOffset]("0" * OffsetHexSize())

trait QMessages {
  def send[M<:Product](local: Context): Context
  // pair of worldProvider.createTx/send can be turned to tx{local=>...} or
  // worldProvider can be not in App, but passed to richServer.init(worldProvider),
  // where richServers wrapped with txTr with AtomicRef;
  // HOWEVER READ-AFTER-WRITE problem here is harder
}

class LongTxWarnPeriod(val value: Long)
class UpdateCompressionMinSize(val value: Long)
class LongAssembleWarnPeriod(val value: Long)

trait ContextFactory { // for tests only
  def updated(updates: List[N_Update]): Context
}

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
  type FieldId = Long
  type SrcId = String
  type TransientMap = Map[TransientLens[_],Object]
  type NextOffset = String
  type TypeKey = ee.cone.c4di.TypeKey
}


trait Injected

trait SharedContext {
  def injected: Injected
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
  val injected: Injected,
  val assembled: ReadModel,
  val executionContext: OuterExecutionContext,
  val transient: TransientMap
) extends SharedContext with AssembledContext

trait GetByPK[V<:Product] extends Product {
  def ofA(context: AssembledContext): Map[SrcId,V]
  //@deprecated def of(context: AssembledContext): Map[SrcId,V] = ???
  def typeKey: TypeKey
  def cl: Class[V]
}

trait DynamicByPK { // low level api, think before use
  def get(joinKey: AssembledKey, context: AssembledContext): Map[SrcId,Product]
}

trait Lens[C,I] extends Getter[C,I] {
  def modify: (I=>I) => C=>C
  def set: I => C=>C
}

trait AbstractLens[C,I] extends Lens[C,I] {
  def modify: (I=>I) => C=>C = f => c => set(f(of(c)))(c)
}

abstract class TransientLens[Item](val default: Item) extends AbstractLens[Context,Item] with Product {
  def of: Context => Item = context => context.transient.getOrElse(this, default).asInstanceOf[Item]
  def set: Item => Context => Context = value => context => new Context(
    context.injected,
    context.assembled,
    context.executionContext,
    context.transient + (this -> value.asInstanceOf[Object])
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
  def apply[P<:Product](p: P): (SrcId,P) = ToPrimaryKey(p) -> p
}

trait LTxAdd {
  def add[M<:Product](out: Seq[LEvent[M]]): Context=>Context
}
trait RawTxAdd {
  def add(out: Seq[N_Update]): Context=>Context
}
trait ReadModelAdd {
  def add(events: Seq[RawEvent], context: AssembledContext): ReadModel
}
trait GetAssembleOptions {
  def get(assembled: ReadModel): AssembleOptions
}

trait Observer[Message] {
  def activate(world: Message): Observer[Message]
}

case object TxTransformOrigMeta{
  def apply(name: String): Context => Context = TxTransformOrigMetaKey.set(MetaAttr(D_TxTransformNameMeta(name)) :: Nil)
}
case object TxTransformOrigMetaKey extends TransientLens[List[MetaAttr]](Nil)

trait TxTransform extends Product {
  def transform(local: Context): Context
}

case object WriteModelKey extends TransientLens[Queue[N_Update]](Queue.empty)

case class RawHeader(key: String, value: String)

trait RawEvent extends Product {
  def srcId: SrcId
  def data: ByteString
  def headers: List[RawHeader]
}
case class SimpleRawEvent(srcId: SrcId, data: ByteString, headers: List[RawHeader]) extends RawEvent

trait GetOffset extends Getter[SharedContext with AssembledContext,NextOffset]

trait RichRawWorldReducer {
  def reduce(context: Option[SharedContext with AssembledContext], events: List[RawEvent]): RichContext
}

trait ProgressObserverFactory {
  def create(endOffset: NextOffset): Observer[RichContext]
}

trait ExtendedRawEvent extends RawEvent {
  def mTime: Long
  def txLogName: TxLogName
  def withContent(headers: List[RawHeader], data: ByteString): ExtendedRawEvent
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
    pairs.groupBy(_._1).transform((k,l)=>Single(l)._2)
}

trait AssembleProfiler {
  def createJoiningProfiling(localOpt: Option[Context]): JoiningProfiling
  def addMeta(transition: WorldTransition, updates: Seq[N_Update]): Future[Seq[N_Update]]
}

case object DebugStateKey extends TransientLens[Option[(RichContext,RawEvent)]](None)

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

class OrigKeyFactoryProposition(val value: KeyFactory)
class OrigKeyFactoryFinalHolder(val value: KeyFactory)

trait UpdateProcessor {
  def process(updates: Seq[N_Update], prevQueueSize: Int): Seq[N_Update]
}

trait UpdateIfChanged {
  def updateSimple[T<:Product](getByPK: GetByPK[T]): Context=>Seq[T]=>Seq[LEvent[Product]]
}

trait GeneralOrigPartitioner
abstract class OrigPartitioner[T<:Product](val cl: Class[T]) extends GeneralOrigPartitioner {
  def handle(value: T): String
  def partitions: Set[String]
}

trait HttpClientProvider {
  def get: Future[HttpClient]
}

trait S3Manager {
  def get(txLogName: TxLogName, resource: String): Future[Option[Array[Byte]]]
  def put(txLogName: TxLogName, resource: String, body: Array[Byte]): Unit
  def delete(txLogName: TxLogName, resource: String): Future[Boolean]
}

trait LOBroker {
  def put(rec: QRecord): QRecord
  def get(events: List[ExtendedRawEvent]): List[ExtendedRawEvent] // this can potentially lead to too big volume in single event list after getting LOB-s
  def bucketPostfix: String
}