
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

object QProtocol extends Protocol {

  @Id(23) case class FailedUpdates(@Id(24) srcId: String, @Id(25) reason: String)

  case class Update(@Id(17) srcId: String, @Id(18) valueTypeId: Long, @Id(19) value: okio.ByteString)

  @Id(20) case class Updates(@Id(17) srcId: String, @Id(21) updates: List[Update])

  @Id(22) case class Firstborn(@Id(17) srcId: String)

  object FailedUpdatesProtoAdapter extends com.squareup.wire.ProtoAdapter[FailedUpdates](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[FailedUpdates]) with ee.cone.c4proto.HasId {
    def id = 23

    def hasId = true

    def className = classOf[FailedUpdates].getName

    def encodedSize(value: FailedUpdates): Int = {
      val FailedUpdates(prep_srcId, prep_reason) = value
      var res = 0
      if (prep_srcId.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(24, prep_srcId)
      if (prep_reason.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(25, prep_reason)
      res
    }

    def encode(writer: com.squareup.wire.ProtoWriter, value: FailedUpdates) = {
      val FailedUpdates(prep_srcId, prep_reason) = value
      if (prep_srcId.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 24, prep_srcId)
      if (prep_reason.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 25, prep_reason)
    }

    def decode(reader: com.squareup.wire.ProtoReader) = {
      var prep_srcId: String = ""
      var prep_reason: String = ""
      val token = reader.beginMessage()
      var done = false
      while (!done) reader.nextTag() match {
        case -1 =>
          done = true
        case 24 =>
          prep_srcId = com.squareup.wire.ProtoAdapter.STRING.decode(reader)
        case 25 =>
          prep_reason = com.squareup.wire.ProtoAdapter.STRING.decode(reader)
        case _ =>
          reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      }
      reader.endMessage(token)
      FailedUpdates(prep_srcId, prep_reason)
    }

    def props = List(ee.cone.c4proto.MetaProp(24, "srcId", "String"), ee.cone.c4proto.MetaProp(25, "reason", "String"))
  }

  object UpdateProtoAdapter extends com.squareup.wire.ProtoAdapter[Update](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[Update]) with ee.cone.c4proto.HasId {
    def id = throw new Exception

    def hasId = false

    def className = classOf[Update].getName

    def encodedSize(value: Update): Int = {
      val Update(prep_srcId, prep_valueTypeId, prep_value) = value
      var res = 0
      if (prep_srcId.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(17, prep_srcId)
      if (prep_valueTypeId != 0L) res += com.squareup.wire.ProtoAdapter.SINT64.encodedSizeWithTag(18, prep_valueTypeId)
      if (prep_value.size > 0) res += com.squareup.wire.ProtoAdapter.BYTES.encodedSizeWithTag(19, prep_value)
      res
    }

    def encode(writer: com.squareup.wire.ProtoWriter, value: Update) = {
      val Update(prep_srcId, prep_valueTypeId, prep_value) = value
      if (prep_srcId.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 17, prep_srcId)
      if (prep_valueTypeId != 0L) com.squareup.wire.ProtoAdapter.SINT64.encodeWithTag(writer, 18, prep_valueTypeId)
      if (prep_value.size > 0) com.squareup.wire.ProtoAdapter.BYTES.encodeWithTag(writer, 19, prep_value)
    }

    def decode(reader: com.squareup.wire.ProtoReader) = {
      var prep_srcId: String = ""
      var prep_valueTypeId: Long = 0
      var prep_value: okio.ByteString = okio.ByteString.EMPTY
      val token = reader.beginMessage()
      var done = false
      while (!done) reader.nextTag() match {
        case -1 =>
          done = true
        case 17 =>
          prep_srcId = com.squareup.wire.ProtoAdapter.STRING.decode(reader)
        case 18 =>
          prep_valueTypeId = com.squareup.wire.ProtoAdapter.SINT64.decode(reader)
        case 19 =>
          prep_value = com.squareup.wire.ProtoAdapter.BYTES.decode(reader)
        case _ =>
          reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      }
      reader.endMessage(token)
      Update(prep_srcId, prep_valueTypeId, prep_value)
    }

    def props = List(ee.cone.c4proto.MetaProp(17, "srcId", "String"), ee.cone.c4proto.MetaProp(18, "valueTypeId", "Long"), ee.cone.c4proto.MetaProp(19, "value", "okio.ByteString"))
  }

  object UpdatesProtoAdapter extends com.squareup.wire.ProtoAdapter[Updates](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[Updates]) with ee.cone.c4proto.HasId {
    def id = 20

    def hasId = true

    def className = classOf[Updates].getName

    def encodedSize(value: Updates): Int = {
      val Updates(prep_srcId, prep_updates) = value
      var res = 0
      if (prep_srcId.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(17, prep_srcId)
      prep_updates.foreach(item => res += UpdateProtoAdapter.encodedSizeWithTag(21, item))
      res
    }

    def encode(writer: com.squareup.wire.ProtoWriter, value: Updates) = {
      val Updates(prep_srcId, prep_updates) = value
      if (prep_srcId.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 17, prep_srcId)
      prep_updates.foreach(item => UpdateProtoAdapter.encodeWithTag(writer, 21, item))
    }

    def decode(reader: com.squareup.wire.ProtoReader) = {
      var prep_srcId: String = ""
      var prep_updates: List[Update] = Nil
      val token = reader.beginMessage()
      var done = false
      while (!done) reader.nextTag() match {
        case -1 =>
          done = true
        case 17 =>
          prep_srcId = com.squareup.wire.ProtoAdapter.STRING.decode(reader)
        case 21 =>
          prep_updates = UpdateProtoAdapter.decode(reader) :: prep_updates
        case _ =>
          reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      }
      reader.endMessage(token)
      prep_updates = prep_updates.reverse
      Updates(prep_srcId, prep_updates)
    }

    def props = List(ee.cone.c4proto.MetaProp(17, "srcId", "String"), ee.cone.c4proto.MetaProp(21, "updates", "List[Update]"))
  }

  object FirstbornProtoAdapter extends com.squareup.wire.ProtoAdapter[Firstborn](com.squareup.wire.FieldEncoding.LENGTH_DELIMITED, classOf[Firstborn]) with ee.cone.c4proto.HasId {
    def id = 22

    def hasId = true

    def className = classOf[Firstborn].getName

    def encodedSize(value: Firstborn): Int = {
      val Firstborn(prep_srcId) = value
      var res = 0
      if (prep_srcId.nonEmpty) res += com.squareup.wire.ProtoAdapter.STRING.encodedSizeWithTag(17, prep_srcId)
      res
    }

    def encode(writer: com.squareup.wire.ProtoWriter, value: Firstborn) = {
      val Firstborn(prep_srcId) = value
      if (prep_srcId.nonEmpty) com.squareup.wire.ProtoAdapter.STRING.encodeWithTag(writer, 17, prep_srcId)
    }

    def decode(reader: com.squareup.wire.ProtoReader) = {
      var prep_srcId: String = ""
      val token = reader.beginMessage()
      var done = false
      while (!done) reader.nextTag() match {
        case -1 =>
          done = true
        case 17 =>
          prep_srcId = com.squareup.wire.ProtoAdapter.STRING.decode(reader)
        case _ =>
          reader.peekFieldEncoding.rawProtoAdapter.decode(reader)
      }
      reader.endMessage(token)
      Firstborn(prep_srcId)
    }

    def props = List(ee.cone.c4proto.MetaProp(17, "srcId", "String"))
  }

  override def adapters = List(FailedUpdatesProtoAdapter, UpdateProtoAdapter, UpdatesProtoAdapter, FirstbornProtoAdapter)
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

case object TxTransformDescription extends TransientLens[String]("")

trait TxTransform extends Product {
  def description: String = this.getClass.getName

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

trait RawSnapshot {
  def save(data: Array[Byte], offset: NextOffset): Unit
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

trait AssembleProfiler {
  def createSerialJoiningProfiling(localOpt: Option[Context]): SerialJoiningProfiling
  def addMeta(profiling: SerialJoiningProfiling, updates: Seq[Update]): Seq[Update]
}
