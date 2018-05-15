
package ee.cone.c4actor.hashsearch.condition

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._

import scala.collection.immutable.Seq

trait SerializationUtilsApp {
  def serializer: SerializationUtils
}

trait SerializationUtilsMix extends SerializationUtilsApp {
  def qAdapterRegistry: QAdapterRegistry

  def serializer: SerializationUtils = SerializationUtils(qAdapterRegistry)
}

case class SerializationUtils(qAdapterRegistry: QAdapterRegistry) {
  def uuidFromOrig(orig: Product, origClName: String): UUID = {
    val adapter = qAdapterRegistry.byName(origClName)
    UUID.nameUUIDFromBytes(adapter.encode(orig))
  }

  def uuidFromOrigOpt(orig: Product, origClName: String): Option[UUID] = {
    val adapterOpt = qAdapterRegistry.byName.get(origClName)
    adapterOpt.map(adapter => UUID.nameUUIDFromBytes(adapter.encode(orig)))
  }

  def uuidFromMetaAttrList(metaAttrs: List[MetaAttr]): UUID =
    uuidFromSeq(metaAttrs.map(uuidFromMetaAttr))

  def uuidFromMetaAttr(metaAttr: MetaAttr): UUID =
    uuidFromSeq(uuid(metaAttr.productPrefix) +: metaAttr.productIterator.map(elem ⇒ uuid(elem.toString)).to[Seq])

  def srcIdFromSrcIds(srcIdList: SrcId*): SrcId =
    uuidFromSrcIdSeq(srcIdList.to[Seq]).toString

  def srcIdFromSrcIds(srcIdList: List[SrcId]): SrcId =
    uuidFromSrcIdSeq(srcIdList).toString

  def uuidFromSrcIdSeq(srcIdList: Seq[SrcId]): UUID =
    uuidFromSeq(srcIdList.map(uuid))

  def uuid(data: String): UUID = UUID.nameUUIDFromBytes(data.getBytes(UTF_8))

  def uuidFromSeq(data: UUID*): UUID = {
    uuidFromSeq(data.to[Seq])
  }

  def uuidFromSeq(data: Seq[UUID]): UUID = {
    val b = ByteBuffer.allocate(java.lang.Long.BYTES * 2 * data.size)
    data.foreach(e ⇒ b.putLong(e.getMostSignificantBits).putLong(e.getLeastSignificantBits))
    UUID.nameUUIDFromBytes(b.array())
  }

  def getConditionPK[Model](modelCl: Class[Model], condition: Condition[Model]): SrcId = {
    def get: Any ⇒ UUID = {
      case c: ProdCondition[_, _] ⇒
        val rq: Product = c.by
        val byClassName = rq.getClass.getName
        val valueAdapterOpt = qAdapterRegistry.byName.get(byClassName)
        valueAdapterOpt match {
          case Some(valueAdapter) ⇒
            val bytesHash = UUID.nameUUIDFromBytes(valueAdapter.encode(rq))
            val byHash = uuid(byClassName) :: bytesHash :: Nil
            val names = c.metaList.collect { case NameMetaAttr(name) ⇒ uuid(name) }
            uuidFromSeq(uuid(modelCl.getName) :: byHash ::: names)
          case None ⇒
            PrintColored("r")(s"[Warning] NonSerializable condition by: ${rq.getClass}")
            uuid(c.toString)
        }
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

