package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor.AnyProtocol.AnyObject
import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.base.{HashSearchAssembleSharedKeys, InnerLeaf}
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryApi, HashSearchRangerRegistryApp, RangerWithCl}
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4actor.AnyAdapter._
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.hashsearch.condition.{SerializationUtils, SerializationUtilsApp}

trait HashSearchDynamicIndexApp
  extends HashSearchRangerRegistryApp
    with QAdapterRegistryApp
    with DefaultModelRegistryApp
    with LensRegistryApp
    with AssemblesApp
    with DynamicIndexModelsApp
    with SerializationUtilsApp {
  override def assembles: List[Assemble] = dynIndexModels.map(model ⇒ new HashSearchDynamicIndexAssemble(
    model.modelCl, model.modelId,
    hashSearchRangerRegistry, defaultModelRegistry, qAdapterRegistry, lensRegistry, serializer
  )
  ) ::: super.assembles
}

sealed trait HashSearchDynamicIndexAssembleUtils {
  def defaultModelRegistry: DefaultModelRegistry

  def qAdapterRegistry: QAdapterRegistry

  def lensRegistryApi: LensRegistryApi

  def serializer: SerializationUtils

  def rangerRegistry: HashSearchRangerRegistryApi

  def prepareRanger[By <: Product, Field](
    byCl: Class[By],
    fieldCl: Class[Field],
    ranger: RangerWithCl[By, Field],
    anyObject: Option[AnyObject]
  ): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = {
    val directive: By = anyObject.map(decode[By](qAdapterRegistry, byCl)(_))
      .getOrElse(defaultModelRegistry.get[By](byCl.getName).create(""))
    ranger.ranges(directive)
  }

  def modelsToHeapIds[Model <: Product, By <: Product, Field](
    byCl: Class[By],
    fieldCl: Class[Field],
    models: Values[Model],
    lensName: List[String],
    fieldToHeaps: Field ⇒ List[By]
  ): Values[(SrcId, Model)] = {
    val lens: ProdLens[Model, Field] = lensRegistryApi.get[Model, Field](lensName)
    models.par.flatMap(model ⇒ { // TODO Can be used with par
      val heaps: List[By] = fieldToHeaps(lens.of(model))
      heapsToSrcIds(lensName, heaps, model.getClass.getName).map(srcId ⇒ srcId → model)
    }
    ).to[Values]
  }

  def modelsToHeapIdsBy[Model <: Product, By <: Product, Field](
    byCl: Class[By],
    fieldCl: Class[Field],
    models: Values[Model],
    lensName: List[String],
    fieldToHeaps: Field ⇒ List[By],
    byRequestAny: AnyObject,
    byToHeaps: PartialFunction[Product, List[By]]
  ): Values[(SrcId, Model)] = {
    val lens: ProdLens[Model, Field] = lensRegistryApi.get[Model, Field](lensName)
    val byRequest: By = decode[By](qAdapterRegistry)(byRequestAny)
    val requestedList = byToHeaps.apply(byRequest)
    models.flatMap(model ⇒ {
      val heaps: List[By] = fieldToHeaps(lens.of(model))
      val intersectedHeap: List[By] = requestedList.intersect(heaps)
      heapsToSrcIds(lensName, intersectedHeap, model.getClass.getName).map(srcId ⇒ srcId → model)
    }
    )
  }

  def heapsToSrcIds[By <: Product](lensName: List[String], heaps: List[By], modelName: String): List[SrcId] = {
    heaps.map(heap ⇒ {
      val metaListUUID = serializer.uuidFromSeq(lensName.map(str ⇒ serializer.uuid(str)))
      val rangeUUID = serializer.uuidFromOrig(heap, heap.getClass.getName)
      val modelUUID = serializer.uuid(modelName)
      val srcId = serializer.uuidFromSeq(modelUUID, metaListUUID, rangeUUID).toString
      s"$modelName$lensName$heap$srcId"
    }
    )
  }

  private def rangerCaster[By <: Product, Field](byCl: Class[By], fieldCl: Class[Field]): RangerWithCl[_, _] ⇒ RangerWithCl[By, Field] =
    _.asInstanceOf[RangerWithCl[By, Field]]

  def modelsToHeaps[Model <: Product](models: Values[Model], node: IndexNodeWithDirective[Model]): Values[(SrcId, Model)] = {
    rangerRegistry.getByIdUntyped(node.indexNode.indexNode.byAdapterId) match {
      case Some(ranger) ⇒
        modelsToHeapsInner(models, ranger.byCl, ranger.fieldCl, ranger, node)
      case None ⇒
        Nil
    }
  }

  private def modelsToHeapsInner[Model <: Product, By <: Product, Field](models: Values[Model], byCl: Class[By], fieldCl: Class[Field], ranger: RangerWithCl[_ <: Product, _], node: IndexNodeWithDirective[Model]): Values[(SrcId, Model)] = {
    val rangerTyped: RangerWithCl[By, Field] = rangerCaster(byCl, fieldCl)(ranger)
    val (left, _): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = prepareRanger(byCl, fieldCl, rangerTyped, node.directive.map(_.directive))
    modelsToHeapIds(byCl, fieldCl, models, node.indexNode.indexNode.lensName, left)
  }

  def modelToHeapsBy[Model <: Product](models: Values[Model], nodeBy: IndexByNodeWithDirective[Model]): Values[(SrcId, Model)] = {
    rangerRegistry.getByIdUntyped(nodeBy.indexNode.indexByNode.byInstance.get.adapterId) match {
      case Some(ranger) ⇒
        modelsToHeapsInnerBy(models, ranger.byCl, ranger.fieldCl, ranger, nodeBy)
      case None ⇒
        Nil
    }
  }

  private def modelsToHeapsInnerBy[Model <: Product, By <: Product, Field](models: Values[Model], byCl: Class[By], fieldCl: Class[Field], ranger: RangerWithCl[_ <: Product, _], nodeBy: IndexByNodeWithDirective[Model]): Values[(SrcId, Model)] = {
    val rangerTyped: RangerWithCl[By, Field] = rangerCaster(byCl, fieldCl)(ranger)
    val (left, right): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = prepareRanger(byCl, fieldCl, rangerTyped, nodeBy.directive.map(_.directive))
    modelsToHeapIdsBy(byCl, fieldCl, models, nodeBy.lensName, left, nodeBy.indexNode.indexByNode.byInstance.get, right)
  }
}

case class RangerDirective[Model <: Product](srcId: SrcId, directive: AnyObject)

case class IndexNodeWithDirective[Model <: Product](
  srcId: SrcId,
  indexNode: IndexNodeRich[Model],
  directive: Option[RangerDirective[Model]]
)

case class IndexByNodeWithDirective[Model <: Product](
  srcId: SrcId,
  lensName: List[String],
  indexNode: IndexByNodeRich[Model],
  directive: Option[RangerDirective[Model]]
)

case class DynamicNeed[Model <: Product](requestId: SrcId)

case class DynamicCount[Model <: Product](heapId: SrcId, count: Int)

trait DynamicIndexSharedTypes {
  type DynamicIndexDirectiveAll = All
}

@assemble class HashSearchDynamicIndexAssemble[Model <: Product](
  modelCl: Class[Model],
  modelId: Int,
  val rangerRegistry: HashSearchRangerRegistryApi,
  val defaultModelRegistry: DefaultModelRegistry,
  val qAdapterRegistry: QAdapterRegistry,
  val lensRegistryApi: LensRegistryApi,
  val serializer: SerializationUtils
) extends Assemble
  with HashSearchAssembleSharedKeys
  with HashSearchDynamicIndexAssembleUtils
  with DynamicIndexSharedTypes {
  type DynamicHeapId = SrcId
  type IndexNodeRichAll = All
  type IndexByNodeRichAll = All
  type LeafConditionId = SrcId

  // Mock DynamicIndexDirectiveAll if none defined
  def DynamicIndexDirectiveMock(
    directiveId: SrcId,
    firstborn: Values[Firstborn]
  ): Values[(DynamicIndexDirectiveAll, RangerDirective[Model])] =
    Nil

  // START: If node.keepAllAlive
  def IndexNodeRichToIndexNodeAll(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]],
    indexNodeRiches: Values[IndexNodeRich[Model]]
  ): Values[(IndexNodeRichAll, IndexNodeWithDirective[Model])] =
    for {
      node ← indexNodeRiches
      if node.keepAllAlive
    } yield {
      val directive: Option[RangerDirective[Model]] = indexNodeDirectives.collectFirst {
        case a if a.directive.adapterId == node.indexNode.byAdapterId ⇒ a
      }
      All → IndexNodeWithDirective(node.srcId, node, directive)
    }

  def ModelToHeapIdByIndexNode(
    modelId: SrcId,
    models: Values[Model],
    @by[IndexNodeRichAll] indexNodeWithDirective: Values[IndexNodeWithDirective[Model]]
  ): Values[(DynamicHeapId, Model)] =
    (for {
      node ← indexNodeWithDirective
    } yield {
      modelsToHeaps(models, node)
    }).flatten

  // END: If node.keepAllAlive

  // START: !If node.keepAllAlive
  def IndexNodeRichToIndexByNodeWithDirectiveAll(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]],
    indexNodeRiches: Values[IndexNodeRich[Model]]
  ): Values[(IndexByNodeRichAll, IndexByNodeWithDirective[Model])] =
    (for {
      node ← indexNodeRiches
      if !node.keepAllAlive
    } yield {
      val directive = indexNodeDirectives.collectFirst {
        case a if a.directive.adapterId == node.indexNode.byAdapterId ⇒ a
      }
      val lensName = node.indexNode.lensName
      for {
        nodeBy ← node.indexByNodes
      } yield {
        All → IndexByNodeWithDirective(nodeBy.srcId, lensName, nodeBy, directive)
      }
    }).flatten

  def ModelToHeapIdByIndexByNode(
    modelId: SrcId,
    models: Values[Model],
    @by[IndexByNodeRichAll] indexByNodeWithDirective: Values[IndexByNodeWithDirective[Model]] // TODO forEach
  ): Values[(DynamicHeapId, Model)] =
    (for {
      nodeBy ← indexByNodeWithDirective
    } yield {
      modelToHeapsBy(models, nodeBy)
    }).flatten

  // END: !If node.keepAllAlive

  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leafConditions: Values[InnerLeaf[Model]],
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]] // TODO forEach
  ): Values[(DynamicHeapId, DynamicNeed[Model])] = {
    Nil
  }

  def DynNeedToDynCountToRequest(
    heapId: SrcId,
    @by[DynamicHeapId] models: Values[Model],
    @by[DynamicHeapId] needs: Values[DynamicNeed[Model]]
  ): Values[(LeafConditionId, DynamicCount[Model])] = {
    Nil
  }

  def DynCountsToCondEstimate(
    
  )

  def Test(
    modelId: SrcId,
    @by[DynamicHeapId] models: Values[Model]
  ): Values[(All, A)] = {
    Seq(All → A(modelId)).to[Values]
  }

  def PrintTest(
    lusdf: SrcId,
    firstborn: Values[Firstborn],
    @by[All] lule: Values[A]
  ): Values[(All, Model)] = {
    PrintColored("", "w")(s"[HEAPS] ${lule.map(_.srcId.slice(41, 80))}")
    Nil
  }
}

case class A(srcId: SrcId)
