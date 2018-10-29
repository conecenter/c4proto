package ee.cone.c4actor.hashsearch.index.dynamic

import java.time.Instant

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.dep.request.{CurrentTimeConfig, CurrentTimeConfigApp}
import ee.cone.c4actor.hashsearch.base.InnerLeaf
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol.{IndexNodeSettings, _}
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryApi, HashSearchRangerRegistryApp, IndexType, RangerWithCl}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._

import scala.collection.immutable
import scala.collection.immutable.Seq

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
    with HashSearchDynamicIndexApp
    with HashSearchRangerRegistryApp
    with DefaultModelRegistryApp {

  def dynamicIndexRefreshRateSeconds: Long

  def dynamicIndexNodeDefaultSetting: IndexNodeSettings = IndexNodeSettings("", false, None)

  override def currentTimeConfig: List[CurrentTimeConfig] =
    CurrentTimeConfig("DynamicIndexAssembleRefresh", dynamicIndexRefreshRateSeconds) ::
      super.currentTimeConfig

  override def assembles: List[Assemble] = {
    modelListIntegrityCheck(dynIndexModels.distinct)
    new ThanosTimeFilters(hashSearchVersion, maxTransforms = dynamicIndexMaxEvents) ::
      dynIndexModels.distinct.map(p ⇒
        new IndexNodeThanos(
          p.modelCl, p.modelId,
          dynamicIndexAssembleDebugMode,
          dynamicIndexAutoStaticNodeCount,
          dynamicIndexAutoStaticLiveSeconds,
          dynamicIndexNodeDefaultSetting,
          dynamicIndexDeleteAnywaySeconds
        )(defaultModelRegistry, qAdapterRegistry, hashSearchRangerRegistry, idGenUtil)
      ) :::
      super.assembles
  }

  def dynamicIndexMaxEvents: Int = 100000

  def dynamicIndexAssembleDebugMode: Boolean = false

  def dynamicIndexAutoStaticNodeCount: Int = 1000

  def dynamicIndexAutoStaticLiveSeconds: Long = 60L * 60L

  def dynamicIndexDeleteAnywaySeconds: Long = 60L * 60L * 24L * 1L

  private def modelListIntegrityCheck: List[ProductWithId[_ <: Product]] ⇒ Unit = list ⇒ {
    val map = list.distinct.groupBy(_.modelId)
    if (map.values.forall(_.size == 1)) {
    } else {
      FailWith.apply(s"Dyn model List contains models with same Id: ${map.filter(_._2.size > 1)}")
    }
  }

  val hashSearchVersion: String = "MC5FLjA=" // note equals to base64 http://base64decode.toolur.com/
}

case class IndexNodeRich[Model <: Product](
  srcId: SrcId,
  isStatic: Boolean,
  indexNode: IndexNode,
  indexByNodes: List[IndexByNodeRich[Model]],
  directive: Option[Any]
)

case class IndexByNodeRichCount[Model <: Product](
  srcId: SrcId,
  indexByNodeCount: Int
)

case class IndexByNodeRich[Model <: Product](
  srcId: SrcId,
  isAlive: Boolean,
  indexByNode: IndexByNode
) {
  lazy val heapIdsSet: Set[String] = indexByNode.heapIds.toSet
}

case class IndexByNodeStats(
  srcId: SrcId,
  lastPongSeconds: Long,
  parentId: SrcId
)

sealed trait ThanosTimeTypes {
  type PowerIndexNodeThanos = All

  type ThanosLEventsTransforms = All
}

@assemble class ThanosTimeFilters(version: String, maxTransforms: Int) extends Assemble with ThanosTimeTypes {

  def SnapTransformWatcher(
    version: SrcId,
    firstBorn: Each[Firstborn],
    versions: Values[IndexNodesVersion]
  ): Values[(SrcId, TxTransform)] =
    if (versions.headOption.map(_.version).getOrElse("") == version) {
      Nil
    } else {
      WithPK(SnapTransform(version)) :: Nil
    }

  def PowerFilterCurrentTimeNode(
    timeNode: SrcId,
    firstborn: Values[Firstborn],
    currentTimeNode: Each[CurrentTimeNode]
  ): Values[(PowerIndexNodeThanos, CurrentTimeNode)] =
    if (currentTimeNode.srcId == "DynamicIndexAssembleRefresh")
      WithAll(currentTimeNode) :: Nil
    else
      Nil


  def ApplyThanosTransforms(
    firsBornId: SrcId,
    firstborn: Values[Firstborn],
    @by[ThanosLEventsTransforms] @distinct events: Values[LEventTransform]
  ): Values[(SrcId, TxTransform)] =
    WithPK(CollectiveTransform("ThanosTX", events.take(maxTransforms))) :: Nil
}

import ee.cone.c4actor.hashsearch.rangers.IndexType._

case class RangerDirective[Model <: Product](
  nodeId: SrcId,
  directive: Any
)

case class PreProcessedLeaf[Model <: Product](
  leafId: SrcId,
  originalLeafIds: List[SrcId],
  indexNodeId: SrcId,
  commonPrefix: String,
  by: Product,
  byId: Long,
  ranger: RangerWithCl[_ <: Product, _]
)

case class ProcessedLeaf[Model <: Product](
  leafId: SrcId,
  originalLeafIds: List[SrcId],
  preProcessed: PreProcessedLeaf[Model],
  heapIds: List[SrcId]
) {
  lazy val heapIdsSet: Set[SrcId] = heapIds.toSet
}

case class IndexNodeTyped[Model <: Product](
  indexNodeId: SrcId,
  indexNode: IndexNode
)

case class IndexByNodeTyped[Model <: Product](
  leafId: SrcId,
  indexByNode: IndexByNode
)

trait IndexNodeThanosUtils[Model <: Product] extends HashSearchIdGeneration {
  def qAdapterRegistry: QAdapterRegistry

  def rangerRegistryApi: HashSearchRangerRegistryApi

  def idGenUtil: IdGenUtil

  def defaultModelRegistry: DefaultModelRegistry

  lazy val nameToIdMap: Map[String, Long] = qAdapterRegistry.byName.transform((_, v) ⇒ if (v.hasId) v.id else -1)
  lazy val longToName: Map[Long, String] = qAdapterRegistry.byId.transform((_, v) ⇒ v.className)

  def modelId: Int

  def preProcessLeaf(
    leaf: InnerLeaf[Model]
  ): List[PreProcessedLeaf[Model]] =
    leaf.prodCondition match {
      case Some(prod) ⇒
        nameToIdMap.get(leaf.byClName) match {
          case Some(byId) ⇒
            rangerRegistryApi.getByByIdUntyped(byId) match {
              case Some(ranger) ⇒
                val preparedBy = innerPrepareLeaf(ranger, prod.by)
                val commonPrefixEv = commonPrefix(modelId, leaf.lensNameList)
                val leafIdEv = leafId(commonPrefixEv, preparedBy)
                val indexNodeIdEv = indexNodeId(commonPrefixEv, byId)
                PreProcessedLeaf[Model](leafIdEv, leaf.srcId :: Nil, indexNodeIdEv, commonPrefixEv, preparedBy, byId, ranger) :: Nil
              case None ⇒ Nil
            }
          case None ⇒ Nil
        }
      case None ⇒ Nil
    }

  def processLeafWDefault(
    leaf: PreProcessedLeaf[Model]
  ): ProcessedLeaf[Model] = {
    val directive = defaultModelRegistry.get[Product](leaf.by.getClass.getName).create("")
    processLeaf(leaf, directive)
  }

  def processLeaf[By <: Product](
    leaf: PreProcessedLeaf[Model],
    directive: By
  ): ProcessedLeaf[Model] = {
    val ids = applyRangerInner(leaf.ranger, leaf.by, directive, leaf.commonPrefix)
    ProcessedLeaf(leaf.leafId, leaf.originalLeafIds, leaf, ids)
  }

  lazy val indexTypeMap: Map[Long, IndexType] = rangerRegistryApi.getAll.map(r ⇒ nameToIdMap(r.byCl.getName) → r.indexType).toMap

  def getIndexType(byId: Long): IndexType = indexTypeMap.getOrElse(byId, IndexType.Default)

  def applyRangerInner[By <: Product](
    ranger: RangerWithCl[By, _],
    by: Product,
    directive: Product,
    commonPrefix: String
  ): List[SrcId] = {
    ranger.ranges(directive.asInstanceOf[By])._2.apply(by).map(heapId(commonPrefix, _))
  }

  def innerPrepareLeaf[By <: Product](
    ranger: RangerWithCl[By, _],
    by: Product
  ): Product = ranger.prepareRequest(by.asInstanceOf[By])

  def prepareDirective(directives: Values[RangerDirective[Model]]): Option[Product] =
    Single.option(directives)

}

@assemble class IndexNodeThanos[Model <: Product](
  modelCl: Class[Model], val modelId: Int,
  debugMode: Boolean,
  autoCount: Int,
  autoLive: Long,
  dynamicIndexNodeDefaultSetting: IndexNodeSettings,
  deleteAnyway: Long
)(
  val defaultModelRegistry: DefaultModelRegistry,
  val qAdapterRegistry: QAdapterRegistry,
  val rangerRegistryApi: HashSearchRangerRegistryApi,
  val idGenUtil: IdGenUtil
)
  extends Assemble
    with ThanosTimeTypes
    with IndexNodeThanosUtils[Model] {

  type IndexNodeId = SrcId
  type FilterPreProcessedLeafs = SrcId

  // Mock join
  def MockJoin(
    srcId: SrcId,
    firstborn: Each[Firstborn]
  ): Values[(SrcId, RangerDirective[Model])] =
    Nil

  //Process Leaf ignores leafsWithAll
  def PreProcessLeaf(
    leafId: SrcId,
    leaf: Each[InnerLeaf[Model]]
  ): Values[(FilterPreProcessedLeafs, PreProcessedLeaf[Model])] =
    preProcessLeaf(leaf).map(WithPK.apply)

  def FilterPreProcessedLeaf(
    leafId: SrcId,
    @by[FilterPreProcessedLeafs] @distinct leafs: Values[PreProcessedLeaf[Model]]
  ): Values[(SrcId, PreProcessedLeaf[Model])] =
    if (leafs.nonEmpty) {
      WithPK(leafs.head.copy(originalLeafIds = leafs.flatMap(_.originalLeafIds).distinct.toList)) :: Nil
    } else {
      Nil
    }

  def PreProcessedLeafToNode(
    leafId: SrcId,
    leaf: Each[PreProcessedLeaf[Model]]
  ): Values[(IndexNodeId, PreProcessedLeaf[Model])] =
    (leaf.indexNodeId → leaf) :: Nil

  def ProcessLeaf(
    nodeId: SrcId,
    directives: Values[RangerDirective[Model]],
    @by[IndexNodeId] preLeafs: Values[PreProcessedLeaf[Model]]
  ): Values[(SrcId, ProcessedLeaf[Model])] =
    Single.option(directives) match {
      case Some(dir) ⇒ preLeafs.map(leaf ⇒ processLeaf(leaf, dir)).map(WithPK.apply)
      case None ⇒ preLeafs.map(leaf ⇒ processLeafWDefault(leaf)).map(WithPK.apply)
    }

  // Node creation
  def IndexNodeFilter(
    indexNodeId: SrcId,
    indexNode: Each[IndexNode]
  ): Values[(SrcId, IndexNodeTyped[Model])] =
    if (indexNode.modelId == modelId)
      WithPK(IndexNodeTyped[Model](indexNode.indexNodeId, indexNode)) :: Nil
    else
      Nil

  def SoulIndexNodeCreation(
    indexNodeId: SrcId,
    indexNodes: Values[IndexNodeTyped[Model]],
    @by[IndexNodeId] leafs: Values[PreProcessedLeaf[Model]]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] =
    (indexNodes.toList, leafs.toList) match {
      case (Nil, leaf :: _) ⇒
        if (debugMode)
          PrintColored("y")(s"[Thanos.Soul, $modelId] Created IndexNode for ${(leaf.by.getClass.getName, leaf.commonPrefix)},${(modelCl.getName, modelId)}")
        val indexType: IndexType = getIndexType(leaf.byId)
        WithAll(SoulTransform(leaf.indexNodeId, modelId, leaf.byId, leaf.commonPrefix, dynamicIndexNodeDefaultSetting, indexType)) :: Nil
      case (indexNode :: Nil, leaf :: _) ⇒
        if (debugMode) {
          val x = indexNode
          val y = leaf
          PrintColored("y")(s"[Thanos.Soul, $modelId] Both alive $x ${y.by}")
        }
        Nil
      case (_ :: Nil, Nil) ⇒
        Nil
      case (Nil, Nil) ⇒
        Nil
      case _ ⇒
        println(s"Multiple indexNodes in [Thanos.Soul, ${modelCl.toString}] - SoulIndexNodeCreation ${indexNodes.toList},${leafs.toList}")
        Nil // WithAll(SoulCorrectionTransform(indexNodeId, indexNodes.map(_.indexNode).toList)) :: Nil
    }

  // ByNode creation
  def IndexByNodeFilter(
    indexNodeId: SrcId,
    indexByNode: Each[IndexByNode]
  ): Values[(SrcId, IndexByNodeTyped[Model])] =
    if (indexByNode.modelId == modelId)
      WithPK(IndexByNodeTyped[Model](indexByNode.leafId, indexByNode)) :: Nil
    else
      Nil

  def RealityInnerLeafIndexByNode(
    innerLeafId: SrcId,
    innerLeafs: Values[ProcessedLeaf[Model]],
    indexByNodes: Values[IndexByNodeTyped[Model]],
    indexByNodesLastSeen: Values[IndexByNodeLastSeen]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] = {
    (innerLeafs.toList, indexByNodes.toList) match {
      case (leaf :: Nil, Nil) ⇒
        if (debugMode)
          PrintColored("r")(s"[Thanos.Reality, $modelId] Created ByNode for ${leaf.preProcessed.by}")
        WithAll(RealityTransform(leaf.preProcessed.leafId, leaf.preProcessed.indexNodeId, leaf.heapIds, leaf.preProcessed.by.toString, modelId, autoLive)) :: Nil
      case (Nil, node :: Nil) ⇒
        if (indexByNodesLastSeen.isEmpty)
          WithAll(MindTransform(node.leafId)) :: Nil
        else
          Nil
      case (leaf :: Nil, node :: Nil) ⇒
        if (debugMode)
          PrintColored("r")(s"[Thanos.Reality, $modelId] Both alive ${leaf.preProcessed.by}")
        if (indexByNodesLastSeen.nonEmpty)
          WithAll(RevertedMindTransform(leaf.leafId)) :: Nil
        else
          Nil
      case (Nil, Nil) ⇒ Nil
      case (a, b) ⇒ FailWith.apply(s"Multiple inputs in [Thanos.Reality, $modelId] - RealityGiveLifeToIndexByNode: $a\n${b.mkString("\n")}")
    }
  }

  // ByNode rich
  def SpaceIndexByNodeRich(
    indexByNodeId: SrcId,
    nodes: Values[IndexByNodeTyped[Model]],
    innerLeafs: Values[ProcessedLeaf[Model]],
    indexByNodesLastSeen: Values[IndexByNodeLastSeen],
    indexByNodeSettings: Values[IndexByNodeSettings],
    @by[PowerIndexNodeThanos] currentTimes: Each[CurrentTimeNode]
  ): Values[(IndexNodeId, IndexByNodeRich[Model])] =
    if (nodes.size == 1) {
      val node = nodes.head
      val currentTime = currentTimes.currentTimeSeconds
      val leafIsPresent = innerLeafs.nonEmpty
      val lastPong = indexByNodesLastSeen.headOption.map(_.lastSeenAtSeconds).getOrElse(0L)
      val setting = indexByNodeSettings.headOption
      val isAlive =
        leafIsPresent || indexByNodesLastSeen.isEmpty ||
          (setting.isDefined && (setting.get.alwaysAlive || currentTime - setting.get.keepAliveSeconds.getOrElse(0L) - lastPong <= 0))
      val rich = IndexByNodeRich[Model](node.leafId, isAlive, node.indexByNode)
      if (debugMode)
        PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexByNodeRich ${(isAlive, currentTime, node.leafId, innerLeafs.headOption.map(_.preProcessed.by))}")
      (rich.indexByNode.indexNodeId → rich) :: Nil
    } else if (innerLeafs.size == 1) {
      val leaf = innerLeafs.head
      val stubIndexByNode = IndexByNode(leaf.leafId, leaf.preProcessed.indexNodeId, modelId, leaf.heapIds, leaf.preProcessed.by.toString)
      val rich = IndexByNodeRich[Model](leaf.leafId, isAlive = true, stubIndexByNode)
      if (debugMode)
        PrintColored("b", "w")(s"[Thanos.Space, $modelId] Created from leaf IndexByNodeRich ${(leaf.leafId, innerLeafs.headOption.map(_.preProcessed.by))}")
      (rich.indexByNode.indexNodeId → rich) :: Nil
    } else Nil

  // NodeRich - dynamic
  def SpaceIndexNodeRichNoneAlive(
    indexNodeId: SrcId,
    indexNode: Each[IndexNodeTyped[Model]],
    indexNodeSettings: Values[IndexNodeSettings],
    @by[IndexNodeId] indexByNodeRiches: Values[IndexByNodeRich[Model]],
    directives: Values[RangerDirective[Model]]
  ): Values[(SrcId, IndexNodeRich[Model])] = {
    val settings = indexNodeSettings.headOption
    val isStatic = (settings.isDefined && settings.get.allAlwaysAlive) || (settings.isDefined && settings.get.keepAliveSeconds.isEmpty && indexByNodeRiches.size > autoCount)
    if (!isStatic) {
      val directive = prepareDirective(directives)
      val rich = IndexNodeRich[Model](indexNode.indexNodeId, isStatic, indexNode.indexNode, indexByNodeRiches.toList, directive)
      if (debugMode)
        PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexNodeRich Dynamic${(isStatic, indexNode.indexNodeId, indexByNodeRiches.size)}")
      WithPK(rich) :: Nil
    } else {
      Nil
    }
  }

  // Count children
  def PowerIndexByNodeCounter(
    indexNodeId: SrcId,
    @by[IndexNodeId] indexByNodeRiches: Values[IndexByNodeRich[Model]]
  ): Values[(SrcId, IndexByNodeRichCount[Model])] =
    WithPK(IndexByNodeRichCount[Model](indexNodeId, indexByNodeRiches.size)) :: Nil

  // NodeRich - static
  def SpaceIndexNodeRichAllAlive(
    indexNodeId: SrcId,
    indexNode: Each[IndexNodeTyped[Model]],
    indexNodeSettings: Values[IndexNodeSettings],
    childCounts: Values[IndexByNodeRichCount[Model]],
    directives: Values[RangerDirective[Model]]
  ): Values[(SrcId, IndexNodeRich[Model])] = {
    val settings = indexNodeSettings.headOption
    val childCount = childCounts.headOption.map(_.indexByNodeCount).getOrElse(0)
    val isAlive = (settings.isDefined && settings.get.allAlwaysAlive) || (settings.isDefined && settings.get.keepAliveSeconds.isEmpty && childCount > autoCount)
    if (isAlive) {
      val directive = prepareDirective(directives)
      val rich = IndexNodeRich[Model](indexNode.indexNodeId, isAlive, indexNode.indexNode, Nil, directive)
      if (debugMode)
        PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexNodeRich Static ${(isAlive, indexNode.indexNodeId)}")
      WithPK(rich) :: Nil
    } else {
      Nil
    }
  }

  // GC Nodes
  def PowerGCIndexByNodes(
    indexNodeRichId: SrcId,
    parent: Each[IndexNodeRich[Model]]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] =
    if (!parent.isStatic)
      for {
        child ← parent.indexByNodes
        if !child.isAlive
      } yield {
        if (debugMode)
          PrintColored("m")(s"[Thanos.Power, $modelId] Deleted ${(child.indexByNode.leafId, child.indexByNode.byStr)}")
        WithAll(PowerTransform(child.srcId, s"Power-${child.srcId}"))
      }
    else Nil

  def PowerGCIndexForStatic(
    indexByNodeId: SrcId,
    indexByNodes: Each[IndexByNodeTyped[Model]],
    indexByNodesLastSeen: Values[IndexByNodeLastSeen],
    @by[PowerIndexNodeThanos] currentTimes: Each[CurrentTimeNode]
  ): Values[(ThanosLEventsTransforms, LEventTransform)] =
    if (indexByNodesLastSeen.nonEmpty && currentTimes.currentTimeSeconds - indexByNodesLastSeen.head.lastSeenAtSeconds > deleteAnyway) {
      WithAll(PowerTransform(indexByNodes.leafId, s"Anyway-${indexByNodes.leafId}")) :: Nil
    } else {
      Nil
    }
}

case class RealityTransform[Model <: Product, By <: Product](srcId: SrcId, parentNodeId: String, heapIds: List[String], byStr: String, modelId: Int, defaultLive: Long) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    val parentOpt: Option[IndexNodeSettings] = ByPK(classOf[IndexNodeSettings]).of(local).get(parentNodeId)
    val settings: immutable.Seq[LEvent[IndexByNodeSettings]] = if (parentOpt.isDefined) {
      val IndexNodeSettings(_, keepAlive, aliveSeconds) = parentOpt.get
      val liveFor = aliveSeconds.getOrElse(defaultLive)
      LEvent.update(IndexByNodeSettings(srcId, keepAlive, Some(liveFor)))
    } else Nil
    val now = Instant.now
    val nowSeconds = now.getEpochSecond
    val firstTime = System.currentTimeMillis()
    val timedLocal: Seq[LEvent[Product]] =
      LEvent.update(IndexByNode(srcId, parentNodeId, modelId, heapIds, byStr)) ++ settings ++ LEvent.delete(IndexByNodeLastSeen(srcId, nowSeconds))
    val secondTime = System.currentTimeMillis()
    LEvent.update(TimeMeasurement(srcId, Option(secondTime - firstTime))) ++ timedLocal
  }
}

case class SoulTransform(srcId: SrcId, modelId: Int, byAdapterId: Long, commonPrefix: String, default: IndexNodeSettings, indexType: IndexType) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    val firstTime = System.currentTimeMillis()
    val IndexNodeSettings(_, alive, time) = default
    val aliveWithType = if (indexType == Static) true else alive
    val timedLocal: Seq[LEvent[Product]] =
      LEvent.update(IndexNode(srcId, modelId, byAdapterId, commonPrefix)) ++
        LEvent.update(IndexNodeSettings(srcId, allAlwaysAlive = aliveWithType, keepAliveSeconds = time))
    val secondTime = System.currentTimeMillis()
    LEvent.update(TimeMeasurement(srcId, Option(secondTime - firstTime))) ++ timedLocal
  }
}

case class PowerTransform(srcId: SrcId, extraKey: String) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] =
    LEvent.delete(IndexByNodeLastSeen(srcId, 0L)) ++ LEvent.delete(IndexByNode(srcId, "", 0, Nil, "")) ++ LEvent.delete(IndexByNodeSettings(srcId, false, None))
}

case class MindTransform(srcId: SrcId) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    val now = Instant.now
    val nowSeconds = now.getEpochSecond
    LEvent.update(IndexByNodeLastSeen(srcId, nowSeconds))
  }
}

case class RevertedMindTransform(srcId: SrcId) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] = {
    LEvent.delete(IndexByNodeLastSeen(srcId, 0L))
  }
}

case class SoulCorrectionTransform(srcId: SrcId, indexNodeList: List[IndexNode]) extends LEventTransform {
  def lEvents(local: Context): Seq[LEvent[Product]] =
    indexNodeList.flatMap(node ⇒ node :: IndexNodeSettings(node.indexNodeId, false, None) :: Nil)
      .flatMap(LEvent.delete)
}

case class SnapTransform(version: String) extends TxTransform {
  def transform(local: Context): Context = {
    val versionW = ByPK(classOf[IndexNodesVersion]).of(local).values.headOption.map(_.version).getOrElse("")
    if (version != versionW) {
      val delete =
        (ByPK(classOf[IndexNodesVersion]).of(local).values ++
          ByPK(classOf[IndexNode]).of(local).values ++
          ByPK(classOf[IndexNodeSettings]).of(local).values ++
          ByPK(classOf[IndexByNodesStats]).of(local).values ++
          ByPK(classOf[IndexByNode]).of(local).values ++
          ByPK(classOf[IndexByNodeLastSeen]).of(local).values ++
          ByPK(classOf[IndexByNodeSettings]).of(local).values ++
          ByPK(classOf[TimeMeasurement]).of(local).values).flatMap(LEvent.delete).toList
      val add = LEvent.update(IndexNodesVersion(version))
      TxAdd(delete ++ add)(local)
    }
    else
      local
  }
}
