package ee.cone.c4actor.hashsearch.index.dynamic

import java.time.Instant

import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.base.{HashSearchModelsApp, InnerLeaf}
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import AnyAdapter._
import ee.cone.c4actor.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.hashsearch.condition.{SerializationUtils, SerializationUtilsApp}

case class ProductWithId[Model <: Product](modelCl: Class[Model], modelId: Int)

trait DynamicIndexModelsApp {
  def dynIndexModels: List[ProductWithId[_ <: Product]] = Nil
}

trait DynamicIndexAssemble
  extends AssemblesApp
    with WithIndexNodeProtocol
    with DynamicIndexModelsApp
    with SerializationUtilsApp
    with CurrentTimeConfigApp
    with HashSearchDynamicIndexApp {

  def dynamicIndexRefreshRateSeconds: Long

  override def currentTimeConfig: List[CurrentTimeConfig] =
    CurrentTimeConfig("DynamicIndexAssembleGC", dynamicIndexRefreshRateSeconds) ::
      CurrentTimeConfig("DynamicIndexAssembleRefresh", dynamicIndexRefreshRateSeconds) ::
      super.currentTimeConfig

  override def assembles: List[Assemble] = {
    modelListIntegrityCheck(dynIndexModels)
    dynIndexModels.distinct.map(p ⇒ new IndexNodeThanos(p.modelCl, p.modelId, dynamicIndexAssembleDebugMode, qAdapterRegistry, serializer, dynamicIndexRefreshRateSeconds)) ::: super.assembles
  }

  def dynamicIndexAssembleDebugMode: Boolean = false

  private def modelListIntegrityCheck: List[ProductWithId[_ <: Product]] ⇒ Unit = list ⇒ {
    val map = list.distinct.groupBy(_.modelId)
    if (map.values.forall(_.size == 1)) {
    } else {
      FailWith.apply(s"Dyn model List contains models with same Id: ${map.filter(_._2.size > 1)}")
    }
  }
}

object IndexNodeThanosUtils {
  def getIndexNodeSrcId(ser: SerializationUtils, modelId: Int, byId: Long, lensName: List[String]): SrcId = {
    val modelSrc = ser.uuid(modelId.toString)
    val bySrc = ser.uuid(byId.toString)
    val lensSrc = ser.uuidFromSrcIdSeq(lensName)
    val caseClassSrc = ser.uuid("IndexNode")
    ser.uuidFromSeq(caseClassSrc, modelSrc, bySrc, lensSrc).toString
  }
}

case class IndexNodeRich[Model <: Product](
  srcId: SrcId,
  keepAllAlive: Boolean,
  indexNode: IndexNode,
  indexNodeSettings: Option[IndexNodeSettings],
  indexByNodes: List[IndexByNodeRich[Model]]
)

case class IndexByNodeRich[Model <: Product](
  srcId: SrcId,
  isAlive: Boolean,
  indexByNode: IndexByNode,
  indexByNodeSettings: Option[IndexByNodeSettings]
)

import IndexNodeThanosUtils._

@assemble class IndexNodeThanos[Model <: Product](
  modelCl: Class[Model],
  modelId: Int,
  debugMode: Boolean,
  qAdapterRegistry: QAdapterRegistry,
  ser: SerializationUtils,
  refreshRateSeconds: Long = 60
) extends Assemble {
  type IndexNodeId = SrcId
  type IndexByNodeId = SrcId

  def SoulLeafToIndexNodeId(
    leafId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]]
  ): Values[(IndexNodeId, InnerLeaf[Model])] =
    for {
      leaf ← innerLeafs
      if leaf.condition.isInstanceOf[ProdCondition[_ <: Product, Model]]
    } yield {
      val prod = leaf.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
      val nameList = prod.metaList.filter(_.isInstanceOf[NameMetaAttr]).map(_.asInstanceOf[NameMetaAttr]).map(_.value)
      getIndexNodeSrcId(ser, modelId, qAdapterRegistry.byName(prod.by.getClass.getName).id, nameList) → leaf
    }

  def SoulIndexNodeCreation(
    indexNodeId: SrcId,
    indexNodes: Values[IndexNode],
    @by[IndexNodeId] leafs: Values[InnerLeaf[Model]]
  ): Values[(SrcId, TxTransform)] =
    (indexNodes.toList, leafs.toList) match {
      case (Nil, Seq(x, xs@_*)) ⇒
        val prod = x.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
        val byId = qAdapterRegistry.byName(prod.by.getClass.getName).id
        val nameList = prod.metaList.filter(_.isInstanceOf[NameMetaAttr]).map(_.asInstanceOf[NameMetaAttr]).map(_.value)
        val srcId = getIndexNodeSrcId(ser, modelId, byId, nameList)
        if (debugMode)
          PrintColored("y")(s"[Thanos.Soul] Created IndexNode for ${(prod.by.getClass.getName, nameList)},${(modelCl.getName, modelId)}")
        WithPK(SoulTransform(srcId, modelId, byId, nameList)) :: Nil
      case (x :: Nil, y) ⇒
        if (debugMode)
          PrintColored("y")(s"[Thanos.Soul] Both alive $x ${y.map(_.condition).head}")
        Nil
      case _ ⇒ FailWith.apply("Multiple indexNodes in [Thanos.Soul] - SoulIndexNodeCreation")
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
          PrintColored("r")(s"[Thanos.Reality] Created ByNode for ${x.condition}")
        val typedCondition = x.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
        val nameList = typedCondition.metaList.filter(_.isInstanceOf[NameMetaAttr]).map(_.asInstanceOf[NameMetaAttr]).map(_.value)
        val parentId = getIndexNodeSrcId(ser, modelId, qAdapterRegistry.byName(typedCondition.by.getClass.getName).id, nameList)
        WithPK(RealityTransform(x.srcId, parentId, encode(qAdapterRegistry)(typedCondition.by))) :: Nil
      case (Nil, _ :: Nil) ⇒ Nil
      case (x :: Nil, y :: Nil) ⇒
        /*if (debugMode)
          PrintColored("r")(s"[Thanos.Reality] Both alive ${x.condition} ${decode(qAdapterRegistry)(y.byInstance.get)}")*/
        Nil
      case (Nil, Nil) ⇒ Nil
      case _ ⇒ FailWith.apply("Multiple inputs in [Thanos.Reality] - RealityGiveLifeToIndexByNode")
    }
  }

  type TimeIndexNodeThanos = All

  def TimeFilterCurrentTimeNode(
    timeNode: SrcId,
    firstborns: Values[Firstborn],
    @by[All] currentTimeNodes: Values[CurrentTimeNode]
  ): Values[(TimeIndexNodeThanos, CurrentTimeNode)] =
    for {
      pong ← currentTimeNodes
      if pong.srcId == "DynamicIndexAssembleRefresh"
    } yield All → pong

  def TimeInnerLeafIndexByNodeToPong(
    innerLeafId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]],
    indexByNodes: Values[IndexByNode],
    @by[TimeIndexNodeThanos] pongs: Values[CurrentTimeNode]
  ): Values[(SrcId, TxTransform)] =
    (for {
      pong ← pongs.headOption.to[Values]
    } yield {
      (innerLeafs, indexByNodes) match {
        case (Seq(x), Seq(y)) ⇒
          if (debugMode)
            PrintColored("g")(s"[Thanos.Time] Both alive, ping ${pong.currentTimeSeconds} ${x.condition} ${decode(qAdapterRegistry)(y.byInstance.get)}")
          WithPK(TimeTransform(y.srcId, pong.currentTimeSeconds)) :: Nil
        case _ ⇒ Nil
      }
    }).flatten

  type PowerIndexNodeThanos = All

  def PowerFilterCurrentTimeNode(
    timeNode: SrcId,
    firstborn: Values[Firstborn],
    @by[All] currentTimeNodes: Values[CurrentTimeNode]
  ): Values[(PowerIndexNodeThanos, CurrentTimeNode)] =
    for {
      pong ← currentTimeNodes
      if pong.srcId == "DynamicIndexAssembleGC"
    } yield WithAll(pong)

  def SpaceIndexByNodeRich(
    indexByNodeId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]],
    indexByNodes: Values[IndexByNode],
    indexByNodeStats: Values[IndexByNodeStats],
    indexByNodeSettings: Values[IndexByNodeSettings],
    @by[PowerIndexNodeThanos] currentTimes: Values[CurrentTimeNode]
  ): Values[(SrcId, IndexByNodeRich[Model])] =
    for {
      node ← indexByNodes
      stats ← indexByNodeStats
    } yield {
      val currentTime = Single(currentTimes).currentTimeSeconds
      val leafIsPresent = innerLeafs.nonEmpty
      val setting = indexByNodeSettings.headOption
      val isAlive = leafIsPresent ||
        (setting.isDefined && (setting.get.alwaysAlive || currentTime - setting.get.keepAliveSeconds.get - stats.lastPongSeconds < 0)) ||
        (currentTime - refreshRateSeconds * 1 - stats.lastPongSeconds < 0)
      val rich = IndexByNodeRich[Model](node.srcId, isAlive, node, setting)
      if (debugMode)
        PrintColored("b", "w")(s"[Thanos.Space] Updated IndexByNodeRich ${(isAlive, node.srcId, innerLeafs.headOption.map(_.condition.asInstanceOf[ProdCondition[_ <: Product, Model]].by))}")
      WithPK(rich)
    }

  def SpaceIndexByNodeRichToIndexNodeId(
    indexByNodeRichId: SrcId,
    indexByNodeRichs: Values[IndexByNodeRich[Model]]
  ): Values[(IndexNodeId, IndexByNodeRich[Model])] =
    for {
      indexByNode ← indexByNodeRichs
    } yield indexByNode.indexByNode.indexNodeId → indexByNode

  def SpaceIndexNodeRich(
    indexNodeId: SrcId,
    indexNodes: Values[IndexNode],
    indexNodeSettings: Values[IndexNodeSettings],
    @by[IndexNodeId] indexByNodeRichs: Values[IndexByNodeRich[Model]]
  ): Values[(SrcId, IndexNodeRich[Model])] =
    for {
      indexNode ← indexNodes
    } yield {
      val settings = indexNodeSettings.headOption
      val isAlive = settings.isDefined && settings.get.allAlwaysAlive
      val rich = IndexNodeRich[Model](indexNode.srcId, isAlive, indexNode, settings, indexByNodeRichs.toList)
      if (debugMode)
        PrintColored("b", "w")(s"[Thanos.Space] Updated IndexNodeRich ${(isAlive, indexNode.srcId, indexByNodeRichs.map(_.srcId.take(5)))}")
      WithPK(rich)
    }

  def PowerGCIndexByNodes(
    indexNodeRichId: SrcId,
    indexNodeRichs: Values[IndexNodeRich[Model]],
    @by[PowerIndexNodeThanos] currentTimes: Values[CurrentTimeNode]
  ): Values[(SrcId, TxTransform)] =
    for {
      parent ← indexNodeRichs
      if !parent.keepAllAlive
      child ← parent.indexByNodes
      if !child.isAlive
    } yield {
      if (debugMode)
        PrintColored("m")(s"[Thanos.Power] Deleted ${(child.indexByNode.srcId, decode(qAdapterRegistry)(child.indexByNode.byInstance.get))}")
      WithPK(PowerTransform(child.srcId))
    }
}

case class RealityTransform[Model <: Product, By <: Product](srcId: SrcId, parentNodeId: String, byInstance: AnyObject) extends TxTransform {
  override def transform(local: Context): Context = {
    TxAdd(
      LEvent.update(
        IndexByNode(srcId, parentNodeId, Some(byInstance))
      )
    )(local)
  }
}

case class TimeTransform(srcId: SrcId, newTime: Long) extends TxTransform {
  override def transform(local: Context): Context = {
    TxAdd(
      LEvent.update(
        IndexByNodeStats(srcId, newTime)
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

case class PowerTransform(srcId: SrcId) extends TxTransform {
  override def transform(local: Context): Context =
    TxAdd(LEvent.delete(IndexByNodeStats(srcId, 0L)) ++ LEvent.delete(IndexByNode(srcId, "", None)))(local)
}
