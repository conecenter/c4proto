package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.ToByteString

trait SerializationUtilsApp {
  def serializer: SerializationUtils
}

trait SerializationUtilsMix extends SerializationUtilsApp {
  def qAdapterRegistry: QAdapterRegistry
  def idGenUtil: IdGenUtil
  def hashGen: HashGen

  def serializer: SerializationUtils = SerializationUtils(idGenUtil, qAdapterRegistry, hashGen)
}

case class SerializationUtils(u: IdGenUtil, qAdapterRegistry: QAdapterRegistry, hashGen: HashGen) { // TODO remove IdGenUtil usage
  def srcIdFromMetaAttrList(metaAttrs: List[MetaAttr]): SrcId = //1
    u.srcIdFromSrcIds(metaAttrs.map(srcIdFromMetaAttr):_*)
  def srcIdFromMetaAttr(metaAttr: MetaAttr): SrcId =
    u.srcIdFromStrings(metaAttr.productPrefix +: metaAttr.productIterator.map(_.toString).to[Seq]:_*)
  def srcIdFromOrig(orig: Product, origClName: String): SrcId = { //2 //todo is it bad, className lost?
    val adapter = qAdapterRegistry.byName(origClName)
    u.srcIdFromSerialized(adapter.id,ToByteString(adapter.encode(orig)))
  }
  def srcIdFromSeqMany(data: SrcId*): SrcId = { //3
    u.srcIdFromSrcIds(data:_*)
  }

  def srcIdFromSrcIds(srcIdList: List[SrcId]): SrcId = //e
    u.srcIdFromSrcIds(srcIdList:_*)

  def getConditionPK[Model](modelCl: Class[Model], condition: Condition[Model]): SrcId = { //e
    def get: Any ⇒ SrcId = {
      case c: ProdCondition[_, _] ⇒
        val rq: Product = c.by
        val byClassName = rq.getClass.getName
        val valueAdapterOpt = qAdapterRegistry.byName.get(byClassName)
        valueAdapterOpt match {
          case Some(valueAdapter) ⇒
            val bytesHash = u.srcIdFromSerialized(0,ToByteString(valueAdapter.encode(rq)))
            val byHash = byClassName :: bytesHash :: Nil
            val names = c.metaList.collect { case NameMetaAttr(name) ⇒ name }
            hashGen.generate(modelCl.getName :: byHash ::: names)
          case None ⇒
            PrintColored("r")(s"[Warning] NonSerializable condition by: ${rq.getClass}")
            hashGen.generate(c.toString)
        }
      case c: Condition[_] ⇒
        hashGen.generate(c.getClass.getName :: c.productIterator.map(get).toList)
    }

    get(condition)
  }
}
