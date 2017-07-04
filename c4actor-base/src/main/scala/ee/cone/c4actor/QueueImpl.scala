
package ee.cone.c4actor

import java.util.UUID

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.{Offset, Update, Updates}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Index, World}
import ee.cone.c4assemble.{Single, WorldKey}
import ee.cone.c4proto.{HasId, Protocol, ToByteString}

import scala.collection.immutable.Seq
import java.nio.charset.StandardCharsets.UTF_8

/*Future[RecordMetadata]*/
//producer.send(new ProducerRecord(topic, rawKey, rawValue))
//decode(new ProtoReader(new okio.Buffer().write(bytes)))
//

class QRecordImpl(val topic: TopicName, val key: Array[Byte], val value: Array[Byte]) extends QRecord {
  def offset: Option[Long] = None
}

class QMessagesImpl(qAdapterRegistry: QAdapterRegistry, getRawQSender: ()⇒RawQSender) extends QMessages {
  import qAdapterRegistry._
  // .map(o⇒ nTx.setLocal(OffsetWorldKey, o+1))
  def send[M<:Product](local: World): World = {
    val tx = TxKey.of(local)
    val updates: List[Update] = tx.toSend.toList
    if(updates.isEmpty) return local
    //println(s"sending: ${updates.size} ${updates.map(_.valueTypeId).map(java.lang.Long.toHexString)}")
    val rawValue = qAdapterRegistry.updatesAdapter.encode(Updates("",updates))
    val rec = new QRecordImpl(InboxTopicName(),Array.empty,rawValue)
    val debugStr = tx.toDebug.map(_.toString).mkString("\n---\n")
    val debugRec = new QRecordImpl(LogTopicName(),Array.empty,debugStr.getBytes(UTF_8))
    val List(offset,_)= getRawQSender().send(List(rec,debugRec))
    OffsetWorldKey.set(offset+1)(local)
  }
  def toUpdate[M<:Product](message: LEvent[M]): Update = {
    val valueAdapter = byName(message.className)
    val byteString = ToByteString(message.value.map(valueAdapter.encode).getOrElse(Array.empty))
    Update(message.srcId, valueAdapter.id, byteString)
  }
  def offsetUpdate(value: Long): List[Update] =
    LEvent.update(Offset("", value)).toList.map(toUpdate)
  def toUpdates(data: Array[Byte]): List[Update] = {
    //if(rec.key.length > 0) throw new Exception
    val updates = qAdapterRegistry.updatesAdapter.decode(data).updates
    updates.filter(u ⇒ qAdapterRegistry.byId.contains(u.valueTypeId))
  }
  def worldOffset: World ⇒ Long = world ⇒
    Single(By.srcId(classOf[Offset]).of(world).getOrElse("",List(Offset("",0L)))).value
  def toTree(updates: Iterable[Update]): Map[WorldKey[Index[SrcId,Product]], Index[SrcId,Product]] =
    updates.groupBy(_.valueTypeId).flatMap { case (valueTypeId, tpUpdates) ⇒
      qAdapterRegistry.byId.get(valueTypeId).map(valueAdapter ⇒
        By.srcId[Product](valueAdapter.className) →
          tpUpdates.groupBy(_.srcId).map { case (srcId, iUpdates) ⇒
            val rawValue = iUpdates.last.value
            val values =
              if(rawValue.size > 0) valueAdapter.decode(rawValue) :: Nil else Nil
            srcId → values
          }
      )
    }
}

object QAdapterRegistryFactory {
  private def checkToMap[K,V](pairs: Seq[(K,V)]): Map[K,V] =
    pairs.groupBy(_._1).transform((k,l)⇒Single(l.toList)._2)
  def apply(protocols: List[Protocol]): QAdapterRegistry = {
    val adapters = protocols.flatMap(_.adapters).asInstanceOf[List[ProtoAdapter[Product] with HasId]]
    val byName = checkToMap(adapters.map(a ⇒ a.className → a))
    val updatesAdapter = byName(classOf[QProtocol.Updates].getName)
      .asInstanceOf[ProtoAdapter[QProtocol.Updates]]
    val byId = checkToMap(adapters.filter(_.hasId).map(a ⇒ a.id → a))
    new QAdapterRegistry(byName, byId, updatesAdapter)
  }
}

class LocalQAdapterRegistryInit(qAdapterRegistry: QAdapterRegistry) extends InitLocal {
  def initLocal: World ⇒ World = QAdapterRegistryKey.set(()⇒qAdapterRegistry)
}
