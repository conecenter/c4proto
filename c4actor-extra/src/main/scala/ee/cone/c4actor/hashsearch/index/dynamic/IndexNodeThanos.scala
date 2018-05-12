package ee.cone.c4actor.hashsearch.index.dynamic

import java.time.Instant

import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.base.{HashSearchModelsApp, InnerLeaf}
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol.{IndexByNode, IndexNode}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import AnyAdapter._
import ee.cone.c4actor.hashsearch.condition.{SerializationUtils, SerializationUtilsApp}

trait DynamicIndexModelsApp {
  def dynIndexModels: List[(Class[_ <: ProductWithId], Int)]
}

trait DynamicIndexAssemble extends AssemblesApp with WithIndexNodeProtocol with DynamicIndexModelsApp with SerializationUtilsApp {
  def qAdapterRegistry: QAdapterRegistry

  override def assembles: List[Assemble] = dynIndexModels.distinct.map(p ⇒ new IndexNodeThanos(p._1, p._2, dynamicIndexAssembleDebugMode, qAdapterRegistry, serializer)) ::: super.assembles

  def dynamicIndexAssembleDebugMode: Boolean = false
}

object IndexNodeThanosUtils {
  def getIndexNodeSrcId(ser: SerializationUtils, modelId: Int, byId: Long): SrcId = {
    val modelSrc = ser.uuid(modelId.toString)
    val bySrc = ser.uuid(byId.toString)
    val caseClassSrc = ser.uuid("IndexNode")
    ser.uuidFromSeq(modelSrc, bySrc, caseClassSrc).toString
  }
}

import IndexNodeThanosUtils._

@assemble class IndexNodeThanos[Model <: ProductWithId](
  modelCl: Class[Model],
  modelId: Int,
  debugMode: Boolean,
  qAdapterRegistry: QAdapterRegistry,
  ser: SerializationUtils
) extends Assemble {
  type IndexNodeId = SrcId

  def SoulLeafToIndexNodeId(
    leafId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]]
  ): Values[(IndexNodeId, InnerLeaf[Model])] =
    for {
      leaf ← innerLeafs
      if leaf.condition.isInstanceOf[ProdCondition[_ <: Product, Model]]
    } yield {
      val prod = leaf.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
      getIndexNodeSrcId(ser, modelId, qAdapterRegistry.byName(prod.by.getClass.getName).id) → leaf
    }

  def SoulIndexNodeCreation(
    indexNodeId: SrcId,
    indexNodes: Values[IndexNode],
    @by[IndexNodeId] leafs: Values[InnerLeaf[Model]]
  ): Values[(SrcId, TxTransform)] =
    (indexNodes.toList, leafs.toList) match {
      case (Nil, Seq(x, xs@_*)) ⇒
        if (debugMode)
          PrintColored("y")(s"[Thanos] Created IndexNode for ${x.condition}")
        val prod = x.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
        val byId = qAdapterRegistry.byName(prod.by.getClass.getName).id
        val srcId = getIndexNodeSrcId(ser, modelId, byId)
        val nameList = prod.metaList.filter(_.isInstanceOf[NameMetaAttr]).map(_.asInstanceOf[NameMetaAttr])
        WithPK(SoulTransform(srcId, modelId, byId, nameList.map(_.value))) :: Nil
      case (_::Nil, _) ⇒ Nil
      case _ ⇒ FailWith("Multiple indexNodes in [Thanos] - SoulIndexNodeCreation")
    }

  def RealityInnerLeafIndexByNode(
    innerLeafId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]],
    indexByNodes: Values[IndexByNode]
  ): Values[(SrcId, TxTransform)] = {
    val filteredLeafs = innerLeafs.filter(_.condition.isInstanceOf[ProdCondition[_ <: Product, Model]])
    (filteredLeafs.toList, indexByNodes.toList) match {
      case (x :: Nil, Nil) ⇒
        if (debugMode)
          PrintColored("r")(s"[Thanos] Created ByNode for ${x.condition}")
        val typedCondition = x.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
        val parentId = getIndexNodeSrcId(ser, modelId, qAdapterRegistry.byName(typedCondition.by.getClass.getName).id)
        WithPK(RealityTransform(x.srcId, parentId, encode(qAdapterRegistry)(typedCondition.by))) :: Nil
      case (Nil, _ :: Nil) ⇒ Nil
      case (x :: Nil, y :: Nil) ⇒
        if (debugMode)
          PrintColored("y")(s"[Thanos] Both alive ${x.condition} ${decode(qAdapterRegistry)(y.byInstance.get)}")
        Nil
      case (Nil, Nil) ⇒ Nil
      case _ ⇒ FailWith.apply("Multiple inputs in [Thanos] - RealityGiveLifeToIndexByNode")
    }
  }
}

case class RealityTransform[Model <: ProductWithId, By <: Product](srcId: SrcId, parentNodeId: String, byInstance: AnyObject) extends TxTransform {
  override def transform(local: Context): Context = {
    val now = Instant.now.getEpochSecond
    TxAdd(
      LEvent.update(
        IndexByNode(srcId, parentNodeId, Some(byInstance))
      )
    )(local)
  }
}

case class SoulTransform(srcId: SrcId, modelId: Int, byAdapterId: Long, lensName: List[String]) extends TxTransform {
  def transform(local: Context): Context =
    TxAdd(
      LEvent.update(
        IndexNode(srcId, modelId, byAdapterId, lensName)
      )
    )(local)
}
