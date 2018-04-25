
package ee.cone.c4actor.hashsearch.base

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.{Condition, NameMetaAttr, ProdCondition, QAdapterRegistry}

import scala.collection.immutable.Seq

class ConditionSerializationUtils(qAdapterRegistry: QAdapterRegistry) {
  def uuid(data: String): UUID =  UUID.nameUUIDFromBytes(data.getBytes(UTF_8))
  def uuidFromSeq(data: Seq[UUID]): UUID = {
    val b = ByteBuffer.allocate(java.lang.Long.BYTES*2*data.size)
    data.foreach(e⇒b.putLong(e.getMostSignificantBits).putLong(e.getLeastSignificantBits))
    UUID.nameUUIDFromBytes(b.array())
  }
  def getPK[Model](modelCl: Class[Model], condition: Condition[Model]): SrcId = {
    val get: Any⇒UUID = {
      case c: ProdCondition[_,_] ⇒
        val rq = c.by
        val byClassName = rq.getClass.getName
        val valueAdapter = qAdapterRegistry.byName(byClassName)
        val bytesHash = UUID.nameUUIDFromBytes(valueAdapter.encode(rq))
        val byHash = uuid(byClassName) :: bytesHash :: Nil
        val names = c.metaList.collect{ case NameMetaAttr(name) ⇒ uuid(name) }
        uuidFromSeq(uuid(modelCl.getName) :: byHash ::: names)
      case c: Condition[_] ⇒
        uuidFromSeq(uuid(c.getClass.getName) :: c.productIterator.map(get).toList)
    }
    get(condition).toString
  }
  /*
    def toBytes(value: Long): Array[Byte] =
      ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

    def stringToKey(value: String): SrcId =
      UUID.nameUUIDFromBytes(value.getBytes(UTF_8)).toString

    def generateByPK(metaList: List[MetaAttr], rq: Product, qAdapterRegistry: QAdapterRegistry, bonusStr: String): SrcId = {
      try {
        val lensNameMetaBytes = metaList.collectFirst { case NameMetaAttr(name) ⇒ name.getBytes(UTF_8) }.getOrElse(Array.emptyByteArray)
        val valueAdapter = qAdapterRegistry.byName(rq.getClass.getName)
        val bytes = valueAdapter.encode(rq)
        UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes ++ lensNameMetaBytes ++ bonusStr.getBytes(UTF_8)).toString
      } catch {
        case _: Exception ⇒ s"Can't serialize $rq $bonusStr $metaList"
      }
    }

    def generatePKFromTwoSrcId(a: SrcId, b: SrcId, bonusStr: String): SrcId = {
      UUID.nameUUIDFromBytes(a.getBytes(UTF_8) ++ b.getBytes(UTF_8) ++ bonusStr.getBytes(UTF_8)).toString
    }*/
}

