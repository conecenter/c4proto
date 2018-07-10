package ee.cone.c4actor.hashsearch.index.dynamic

import java.time.Instant

import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.hashsearch.base.{HashSearchModelsApp, InnerLeaf}
import ee.cone.c4actor.hashsearch.index.dynamic.IndexNodeProtocol.{IndexNodeSettings, _}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import AnyAdapter._
import ee.cone.c4actor.AnyOrigProtocol.AnyOrig
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.dep.request.{CurrentTimeConfig, CurrentTimeConfigApp}

import scala.collection.immutable

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
    CurrentTimeConfig("DynamicIndexAssembleGC", dynamicIndexRefreshRateSeconds * 5) ::
      CurrentTimeConfig("DynamicIndexAssembleRefresh", dynamicIndexRefreshRateSeconds) ::
      super.currentTimeConfig

  override def assembles: List[Assemble] = {
    modelListIntegrityCheck(dynIndexModels)
    new ThanosTimeFilters ::
      dynIndexModels.distinct.map(p ⇒
        new IndexNodeThanos(
          p.modelCl, p.modelId,
          dynamicIndexAssembleDebugMode, qAdapterRegistry, serializer,
          dynamicIndexRefreshRateSeconds, dynamicIndexAutoStaticNodeCount, dynamicIndexAutoStaticLiveSeconds
        )
      ) :::
      super.assembles
  }

  def dynamicIndexAssembleDebugMode: Boolean = false

  def dynamicIndexAutoStaticNodeCount: Int = 1000

  def dynamicIndexAutoStaticLiveSeconds: Long = 60L * 60L

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
    /*val modelSrc = ser.uuid(modelId.toString)
    val bySrc = ser.uuid(byId.toString)
    val lensSrc = ser.uuidFromSrcIdSeq(lensName)
    val caseClassSrc = ser.uuid("IndexNode")
    ser.uuidFromSeq(caseClassSrc, modelSrc, bySrc, lensSrc).toString*/
    ser.srcIdFromSrcIds("IndexNode" :: modelId.toString :: byId.toString :: lensName)
  }
}

case class IndexNodeRich[Model <: Product](
  srcId: SrcId,
  keepAllAlive: Boolean,
  indexNode: IndexNode,
  indexByNodes: List[IndexByNodeRich[Model]]
)

case class IndexByNodeRich[Model <: Product](
  srcId: SrcId,
  isAlive: Boolean,
  indexByNode: IndexByNode
)

import IndexNodeThanosUtils._

sealed trait ThanosTimeTypes {
  type TimeIndexNodeThanos = All

  type PowerIndexNodeThanos = All
}

@assemble class ThanosTimeFilters(refreshRateSeconds: Long = 60) extends Assemble with ThanosTimeTypes {
  def TimeFilterCurrentTimeNode(
    timeNode: SrcId,
    firstborns: Values[Firstborn],
    @by[All] currentTimeNodes: Values[CurrentTimeNode]
  ): Values[(TimeIndexNodeThanos, CurrentTimeNode)] =
    (for {
      pong ← currentTimeNodes
      if pong.srcId == "DynamicIndexAssembleRefresh"
    } yield WithAll(pong)).headOption.to[Values]

  def PowerFilterCurrentTimeNode(
    timeNode: SrcId,
    firstborn: Values[Firstborn],
    @by[All] currentTimeNodes: Values[CurrentTimeNode]
  ): Values[(PowerIndexNodeThanos, CurrentTimeNode)] =
    (for {
      pong ← currentTimeNodes
      if pong.srcId == "DynamicIndexAssembleRefresh"
    } yield WithAll(CurrentTimeNode("DynamicIndexAssembleGC", pong.currentTimeSeconds / (refreshRateSeconds * 5) * (refreshRateSeconds * 5)))).headOption.to[Values]
}

@assemble class IndexNodeThanos[Model <: Product](
  modelCl: Class[Model],
  modelId: Int,
  debugMode: Boolean,
  qAdapterRegistry: QAdapterRegistry,
  ser: SerializationUtils,
  refreshRateSeconds: Long = 60,
  autoCount: Int,
  autoLive: Long
) extends Assemble with ThanosTimeTypes {
  type IndexNodeId = SrcId
  type IndexByNodeId = SrcId

  def SoulLeafToIndexNodeId(
    leafId: SrcId,
    leaf: Each[InnerLeaf[Model]]
  ): Values[(IndexNodeId, InnerLeaf[Model])] =
    leaf.condition match {
      case prod: ProdCondition[_, Model] ⇒
        val nameList = prod.metaList.collect { case a: NameMetaAttr ⇒ a.value }
        val byIdOpt = qAdapterRegistry.byName.get(prod.by.getClass.getName).map(_.id)
        if (byIdOpt.isDefined) {
          (getIndexNodeSrcId(ser, modelId, byIdOpt.get, nameList) → leaf) :: Nil
        } else {
          Nil
        }
      case _ ⇒ Nil
    }

  def SoulIndexNodeCreation(
    indexNodeId: SrcId,
    indexNodes: Values[IndexNode],
    @by[IndexNodeId] leafs: Values[InnerLeaf[Model]]
  ): Values[(SrcId, TxTransform)] =
    (indexNodes.toList.filter(_.modelId == modelId), leafs.toList) match {
      case (Nil, Seq(x, xs@_*)) ⇒
        val prod = x.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
        val byIdOpt = qAdapterRegistry.byName.get(prod.by.getClass.getName).map(_.id)
        val nameList = prod.metaList.filter(_.isInstanceOf[NameMetaAttr]).map(_.asInstanceOf[NameMetaAttr]).map(_.value)
        byIdOpt match {
          case Some(byId) =>
            val srcId = getIndexNodeSrcId(ser, modelId, byId, nameList)
            if (debugMode)
              PrintColored("y")(s"[Thanos.Soul, $modelId] Created IndexNode for ${(prod.by.getClass.getName, nameList)},${(modelCl.getName, modelId)}")
            WithPK(SoulTransform(srcId, modelId, byId, nameList)) :: Nil
          case None =>
            PrintColored("r")(s"[Thanos.Soul, $modelId] Non serializable condition: $prod")
            Nil
        }
      case (x :: Nil, Seq(y, ys@_*)) ⇒
        if (debugMode)
          PrintColored("y")(s"[Thanos.Soul, $modelId] Both alive $x ${y.condition}")
        Nil
      case (_ :: Nil, Seq()) ⇒
        Nil
      case (Nil, Seq()) ⇒
        Nil
      case _ ⇒ FailWith.apply("Multiple indexNodes in [Thanos.Soul, $modelId] - SoulIndexNodeCreation")
    }

  def RealityInnerLeafIndexByNode(
    innerLeafId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]],
    indexByNodes: Values[IndexByNode]
  ): Values[(SrcId, TxTransform)] = {
    val filteredLeafs = innerLeafs.filter(_.condition.isInstanceOf[ProdCondition[_ <: Product, Model]])
    (filteredLeafs.toList, indexByNodes.toList.filter(_.modelId == modelId)) match {
      case (x :: Nil, Nil) ⇒
        if (debugMode)
          PrintColored("r")(s"[Thanos.Reality, $modelId] Created ByNode for ${x.condition}")
        val typedCondition = x.condition.asInstanceOf[ProdCondition[_ <: Product, Model]]
        val nameList = typedCondition.metaList.filter(_.isInstanceOf[NameMetaAttr]).map(_.asInstanceOf[NameMetaAttr]).map(_.value)
        val byIdOpt = qAdapterRegistry.byName.get(typedCondition.by.getClass.getName).map(_.id)
        byIdOpt match {
          case Some(byId) =>
            val parentId = getIndexNodeSrcId(ser, modelId, byId, nameList)
            WithPK(RealityTransform(x.srcId, parentId, encode(qAdapterRegistry)(typedCondition.by), modelId, autoLive)) :: Nil
          case None =>
            Nil
        }
      case (Nil, _ :: Nil) ⇒ Nil
      case (x :: Nil, y :: Nil) ⇒
        if (debugMode)
          PrintColored("r")(s"[Thanos.Reality, $modelId] Both alive ${x.condition} ${decode(qAdapterRegistry)(y.byInstance.get)}")
        Nil
      case (Nil, Nil) ⇒ Nil
      case _ ⇒ FailWith.apply(s"Multiple inputs in [Thanos.Reality, $modelId] - RealityGiveLifeToIndexByNode")
    }
  }

  def TimeInnerLeafIndexByNodeToPong(
    innerLeafId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]],
    indexByNodes: Values[IndexByNode],
    indexByNodeStats: Values[IndexByNodeStats],
    @by[TimeIndexNodeThanos] pong: Each[CurrentTimeNode]
  ): Values[(SrcId, TxTransform)] =
    (innerLeafs, indexByNodes.filter(_.modelId == modelId), indexByNodeStats) match {
      case (Seq(x), Seq(y), z) ⇒
        if (z.isEmpty || (z.nonEmpty && (pong.currentTimeSeconds != z.head.lastPongSeconds))) {
          if (debugMode)
            PrintColored("g")(s"[Thanos.Time, $modelId] Both alive, ping ${pong.currentTimeSeconds} ${x.condition} ${decode(qAdapterRegistry)(y.byInstance.get)}")
          WithPK(TimeTransform(y.srcId, pong.currentTimeSeconds)) :: Nil
        } else {
          Nil
        }
      case _ ⇒ Nil
    }


  def SpaceIndexByNodeRich(
    indexByNodeId: SrcId,
    innerLeafs: Values[InnerLeaf[Model]],
    node: Each[IndexByNode],
    indexByNodeStats: Values[IndexByNodeStats],
    indexByNodeSettings: Values[IndexByNodeSettings],
    @by[PowerIndexNodeThanos] currentTimes: Each[CurrentTimeNode]
  ): Values[(SrcId, IndexByNodeRich[Model])] =
    if (node.modelId == modelId)
      for {
        stats ← indexByNodeStats
      } yield {
        val currentTime = currentTimes.currentTimeSeconds
        val leafIsPresent = innerLeafs.nonEmpty
        val setting = indexByNodeSettings.headOption
        val isAlive =
          leafIsPresent ||
            (setting.isDefined &&
              (setting.get.alwaysAlive || currentTime - setting.get.keepAliveSeconds.getOrElse(0L) - stats.lastPongSeconds <= 0)) ||
            (setting.isEmpty && currentTime - refreshRateSeconds * 5 - stats.lastPongSeconds > 0)
        val rich = IndexByNodeRich[Model](node.srcId, isAlive, node)
        if (debugMode)
          PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexByNodeRich ${(isAlive, currentTime, node.srcId, innerLeafs.headOption.map(_.condition.asInstanceOf[ProdCondition[_ <: Product, Model]].by))}")
        WithPK(rich)
      }
    else Nil

  def SpaceIndexByNodeRichToIndexNodeId(
    indexByNodeRichId: SrcId,
    indexByNode: Each[IndexByNodeRich[Model]]
  ): Values[(IndexNodeId, IndexByNodeRich[Model])] =
    List(indexByNode.indexByNode.indexNodeId → indexByNode)

  def SpaceIndexNodeRichNoneAlive(
    indexNodeId: SrcId,
    indexNode: Each[IndexNode],
    indexNodeSettings: Values[IndexNodeSettings],
    @by[IndexNodeId] indexByNodeRiches: Values[IndexByNodeRich[Model]]
  ): Values[(SrcId, IndexNodeRich[Model])] =
    if (indexNode.modelId == modelId) {
      val settings = indexNodeSettings.headOption
      val isAlive = (settings.isDefined && settings.get.allAlwaysAlive) || (settings.isDefined && settings.get.keepAliveSeconds.isEmpty && indexByNodeRiches.size > autoCount)
      if (!isAlive) {
        val rich = IndexNodeRich[Model](indexNode.srcId, isAlive, indexNode, indexByNodeRiches.toList)
        if (debugMode)
          PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexNodeRich None Alive ${(isAlive, indexNode.srcId, indexByNodeRiches.size)}")
        WithPK(rich) :: Nil
      } else {
        Nil
      }
    }
    else Nil

  def SpaceIndexNodeRichAllAlive(
    indexNodeId: SrcId,
    indexNode: Each[IndexNode],
    indexNodeSettings: Values[IndexNodeSettings]
  ): Values[(SrcId, IndexNodeRich[Model])] =
    if (indexNode.modelId == modelId) {
      val settings = indexNodeSettings.headOption
      val isAlive = settings.isDefined && settings.get.allAlwaysAlive
      if (isAlive) {
        val rich = IndexNodeRich[Model](indexNode.srcId, isAlive, indexNode, Nil)
        if (debugMode)
          PrintColored("b", "w")(s"[Thanos.Space, $modelId] Updated IndexNodeRich All alive ${(isAlive, indexNode.srcId)}")
        WithPK(rich) :: Nil
      } else {
        Nil
      }
    }
    else Nil

  def PowerGCIndexByNodes(
    indexNodeRichId: SrcId,
    parent: Each[IndexNodeRich[Model]]
  ): Values[(SrcId, TxTransform)] =
    if (!parent.keepAllAlive)
      for {
        child ← parent.indexByNodes
        if !child.isAlive
      } yield {
        if (debugMode)
          PrintColored("m")(s"[Thanos.Power, $modelId] Deleted ${(child.indexByNode.srcId, decode(qAdapterRegistry)(child.indexByNode.byInstance.get))}")
        WithPK(PowerTransform(child.srcId, parent.srcId))
      }
    else Nil
}

case class RealityTransform[Model <: Product, By <: Product](srcId: SrcId, parentNodeId: String, byInstance: AnyOrig, modelId: Int, defaultLive: Long) extends TxTransform {
  override def transform(local: Context): Context = {
    val parentOpt: Option[IndexNodeSettings] = ByPK(classOf[IndexNodeSettings]).of(local).get(parentNodeId)
    val settings: immutable.Seq[LEvent[IndexByNodeSettings]] = if (parentOpt.isDefined) {
      val IndexNodeSettings(_, keepAlive, aliveSeconds) = parentOpt.get
      val liveFor = aliveSeconds.getOrElse(defaultLive)
      LEvent.update(IndexByNodeSettings(srcId, keepAlive, Some(liveFor)))
    } else Nil
    val now = Instant.now
    val nowSeconds = now.getEpochSecond
    val firstTime = System.currentTimeMillis()
    val timedLocal = TxAdd(
      LEvent.update(IndexByNode(srcId, parentNodeId, modelId, Some(byInstance))) ++ settings ++ LEvent.update(IndexByNodeStats(srcId, nowSeconds))
    )(local)
    val secondTime = System.currentTimeMillis()
    TxAdd(LEvent.update(TimeMeasurement(srcId, Option(secondTime - firstTime))))(timedLocal)
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
  def transform(local: Context): Context = {
    val firstTime = System.currentTimeMillis()
    val timedLocal = TxAdd(
      LEvent.update(IndexNode(srcId, modelId, byAdapterId, lensName)) ++
        LEvent.update(IndexNodeSettings(srcId, false, None))
    )(local)
    val secondTime = System.currentTimeMillis()
    TxAdd(LEvent.update(TimeMeasurement(srcId, Option(secondTime - firstTime))))(timedLocal)
  }
}

case class PowerTransform(srcId: SrcId, parentId: SrcId) extends TxTransform {
  override def transform(local: Context): Context =
    TxAdd(LEvent.delete(IndexByNodeStats(srcId, 0L)) ++ LEvent.delete(IndexByNode(srcId, "", 0, None)))(local)
}
