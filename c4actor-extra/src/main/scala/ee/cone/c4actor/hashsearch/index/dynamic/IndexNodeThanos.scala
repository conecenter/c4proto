package ee.cone.c4actor.hashsearch.index.dynamic

import java.time.Instant

import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.base.{HashSearchModelsApp, InnerLeaf}
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol.IndexByNode
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble}
import AnyAdapter._

trait DynamicIndexAssemble extends HashSearchModelsApp with AssemblesApp with WithIndexNodeProtocol {
  def qAdapterRegistry: QAdapterRegistry

  override def assembles: List[Assemble] = hashSearchModels.distinct.map(new IndexNodeThanos(_, dynamicIndexAssembleDebugMode, qAdapterRegistry)) ::: super.assembles

  def dynamicIndexAssembleDebugMode: Boolean = false
}

@assemble class IndexNodeThanos[Model <: Product](
  modelCl: Class[Model],
  debugMode: Boolean,
  qAdapterRegistry: QAdapterRegistry
) extends Assemble {

  def RealityGiveNTakeLifeIndexByNode(
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
        WithPK(RealityTransform(x.srcId, modelCl, typedCondition.by.getClass, encode(qAdapterRegistry)(typedCondition.by))) :: Nil
      case (Nil, x :: Nil) ⇒
        if (!x.alwaysAlive) {
          if (debugMode)
            PrintColored("g")(s"[Thanos] Waiting for timeout ByNode ${encode(qAdapterRegistry)(x)}")
          WithPK(TimeTransform(x.srcId, x)) :: Nil
        } else {
          if (debugMode)
            PrintColored("g")(s"[Thanos] ByNode ${encode(qAdapterRegistry)(x)} is alwaysAlive")
          Nil
        }
      case (x :: Nil, y :: Nil) ⇒
        if (debugMode)
          PrintColored("y")(s"[Thanos] Both alive ${x.condition} ${encode(qAdapterRegistry)(y.byInstance.get)}")
        Nil
      case (Nil, Nil) ⇒ Nil
      case _ ⇒ FailWith.apply("Multiple inputs in Thanos - RealityGiveLifeToIndexByNode")
    }
  }
}

case class RealityTransform[Model <: Product, By <: Product](srcId: SrcId, modelCl: Class[Model], byCl: Class[By], byInstance: AnyObject) extends TxTransform {
  override def transform(local: Context): Context = {
    val now = Instant.now.getEpochSecond
    TxAdd(
      LEvent.update(
        IndexByNode(srcId, modelCl.getName, byCl.getName, alwaysAlive = true, now, None, Some(byInstance))
      )
    )(local)
  }
}

case class TimeTransform(srcId: SrcId, byNode: IndexByNode) extends TxTransform {
  override def transform(local: Context): Context = {
    if (!byNode.alwaysAlive) {
      val now = Instant.now.getEpochSecond
      val lifeTime = byNode.creationTimeSeconds + byNode.keepAliveSeconds.get
      if (lifeTime < now)
        TxAdd(LEvent.delete(byNode))(local)
      else
        local
    } else {
      local
    }
  }
}
