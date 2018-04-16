
package ee.cone.c4actor

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

import ee.cone.c4actor.Types.SrcId

trait ConditionFactoryMix {
  def conditionFactory: ModelConditionFactory[_] = new ModelConditionFactoryImpl[Unit]()
}

class ModelConditionFactoryImpl[Model] extends ModelConditionFactory[Model] {
  def of[OtherModel]: ModelConditionFactory[OtherModel] =
    new ModelConditionFactoryImpl[OtherModel]

  def ofWithCl[OtherModel]: Class[OtherModel] ⇒ ModelConditionFactory[OtherModel] = cl ⇒
    new ModelConditionFactoryImpl[OtherModel]

  def intersect: (Condition[Model], Condition[Model]) ⇒ Condition[Model] =
    IntersectCondition(_, _)

  def union: (Condition[Model], Condition[Model]) ⇒ Condition[Model] =
    UnionCondition(_, _)

  def any: Condition[Model] =
    AnyCondition()

  def leaf[By <: Product, Field](lens: ProdLens[Model, Field], by: By, byOptions: List[MetaAttr])(
    implicit check: ConditionCheck[By, Field]
  ): Condition[Model] = {
    val preparedBy = check.prepare(byOptions)(by)
    ProdConditionImpl(filterMetaList(lens), preparedBy)(check.check(preparedBy), lens.of)
  }

  def filterMetaList[Field]: ProdLens[Model, Field] ⇒ List[MetaAttr] =
    _.metaList.collect { case l: NameMetaAttr ⇒ l }
}

import ConditionSerializationUtils._

case class ProdConditionImpl[By <: Product, Model, Field](
  metaList: List[MetaAttr], by: By
)(
  fieldCheck: Field ⇒ Boolean, of: Model ⇒ Field
) extends ProdCondition[By, Model] {
  def check(model: Model): Boolean = fieldCheck(of(model))

  def getPK(modelCl: Class[Model]): QAdapterRegistry => String = reg ⇒ generateByPK(metaList, by, reg, "Leaf")
}

case class IntersectCondition[Model](
  left: Condition[Model],
  right: Condition[Model]
) extends SerializableCondition[Model] {
  def check(line: Model): Boolean = left.check(line) && right.check(line)

  def getPK(modelCl: Class[Model]): QAdapterRegistry => String = reg ⇒ {
    val id1 = left.asInstanceOf[SerializableCondition[Model]].getPK(modelCl)(reg)
    val id2 = right.asInstanceOf[SerializableCondition[Model]].getPK(modelCl)(reg)
    generatePKFromTwoSrcId(id1, id2, "Intersect")
  }
}

case class UnionCondition[Model](
  left: Condition[Model],
  right: Condition[Model]
) extends SerializableCondition[Model] {
  def check(line: Model): Boolean = left.check(line) || right.check(line)

  def getPK(modelCl: Class[Model]): QAdapterRegistry => String = reg ⇒ {
    val id1 = left.asInstanceOf[SerializableCondition[Model]].getPK(modelCl)(reg)
    val id2 = right.asInstanceOf[SerializableCondition[Model]].getPK(modelCl)(reg)
    generatePKFromTwoSrcId(id1, id2, "Union")
  }
}

case class AnyCondition[Model]() extends SerializableCondition[Model] {
  def check(line: Model): Boolean = true

  def getPK(modelCl: Class[Model]): QAdapterRegistry => String = _ ⇒ stringToKey(s"Any:${modelCl.getName}")
}

object ConditionSerializationUtils {
  def toBytes(value: Long): Array[Byte] =
    ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()

  def stringToKey(value: String): SrcId =
    UUID.nameUUIDFromBytes(value.getBytes(UTF_8)).toString

  def generateByPK(metaList: List[MetaAttr], rq: Product, qAdapterRegistry: QAdapterRegistry, bonusStr: String): SrcId = {
    val lensNameMetaBytes = metaList.collectFirst { case NameMetaAttr(name) ⇒ name.getBytes(UTF_8) }.getOrElse(Array.emptyByteArray)
    val valueAdapter = qAdapterRegistry.byName(rq.getClass.getName)
    val bytes = valueAdapter.encode(rq)
    UUID.nameUUIDFromBytes(toBytes(valueAdapter.id) ++ bytes ++ lensNameMetaBytes ++ bonusStr.getBytes(UTF_8)).toString
  }

  def generatePKFromTwoSrcId(a: SrcId, b: SrcId, bonusStr: String): SrcId = {
    UUID.nameUUIDFromBytes(a.getBytes(UTF_8) ++ b.getBytes(UTF_8) ++ bonusStr.getBytes(UTF_8)).toString
  }
}

