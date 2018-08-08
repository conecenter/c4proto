package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.base._
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryApi, HashSearchRangerRegistryApp, RangerWithCl}
import ee.cone.c4assemble._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4actor.AnyAdapter._
import ee.cone.c4actor.AnyOrigProtocol.AnyOrig
import ee.cone.c4actor.QProtocol.Firstborn

trait HashSearchDynamicIndexApp
  extends HashSearchRangerRegistryApp
    with QAdapterRegistryApp
    with DefaultModelRegistryApp
    with LensRegistryApp
    with AssemblesApp
    with DynamicIndexModelsApp
    with SerializationUtilsApp {
  override def assembles: List[Assemble] = dynIndexModels.distinct.map(model ⇒ new HashSearchDynamicIndexAssemble(
    model.modelCl, model.modelId,
    hashSearchRangerRegistry, defaultModelRegistry, qAdapterRegistry, lensRegistry, serializer
  )(model.modelCl)
  ) ::: super.assembles
}

object DynIndexTypes {
  type DecodedBy = Product
}

import DynIndexTypes._

// By is Orig:
//   lens is obligatory
//   ranger is optional
sealed trait HashSearchDynamicIndexAssembleUtils[Model <: Product] {

  lazy val anyModelKey: SrcId = serializer.u.srcIdFromStrings(modelClass.getName, modelId.toString)

  def decodeBy: AnyOrig ⇒ DecodedBy = AnyAdapter.decodeProduct(qAdapterRegistry)

  def modelClass: Class[Model]

  def modelId: Int

  def defaultModelRegistry: DefaultModelRegistry

  def qAdapterRegistry: QAdapterRegistry

  def lensRegistryApi: LensRegistryApi

  def serializer: SerializationUtils

  def rangerRegistry: HashSearchRangerRegistryApi

  def cDynEstimate(cond: InnerLeaf[Model], priorities: Values[DynamicCount[Model]]): Values[InnerConditionEstimate[Model]] = {
    val priorPrep = priorities.distinct
    if (priorPrep.nonEmpty)
      InnerConditionEstimate[Model](cond.srcId, Log2Pow2(priorPrep.map(_.count).sum), priorPrep.map(_.heapId).toList) :: Nil
    else
      Nil
  }

  def prepareRanger[By <: Product, Field](
    byCl: Class[By],
    fieldCl: Class[Field],
    ranger: RangerWithCl[By, Field],
    anyObject: Option[DecodedBy]
  ): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = {
    val directive: By = anyObject.map(_.asInstanceOf[By])
      .getOrElse(defaultModelRegistry.get[By](byCl.getName).create(""))
    ranger.ranges(directive)
  }

  def modelsToHeapIds[By <: Product, Field](
    byCl: Class[By],
    fieldCl: Class[Field],
    models: Values[Model],
    lensName: List[String],
    fieldToHeaps: Field ⇒ List[By]
  ): Values[(SrcId, Model)] = {
    val lensOpt: Option[ProdLens[Model, Field]] = lensRegistryApi.getOpt[Model, Field](lensName)
    lensOpt match {
      case Some(lens) =>
        models.flatMap(model ⇒ {
          val heaps: List[By] = fieldToHeaps(lens.of(model))
          heapsToSrcIds(lensName, heaps).map(srcId ⇒ srcId → model)
        }
        )
      case None ⇒
        Nil
    }

  }

  def modelsToHeapIdsBy[By <: Product, Field](
    byCl: Class[By],
    fieldCl: Class[Field],
    models: Values[Model],
    lensName: List[String],
    fieldToHeaps: Field ⇒ List[By],
    byRequestAny: DecodedBy,
    byToHeaps: PartialFunction[Product, List[By]]
  ): Values[(SrcId, Model)] = {
    val lensOpt: Option[ProdLens[Model, Field]] = lensRegistryApi.getOpt[Model, Field](lensName)
    lensOpt match {
      case Some(lens) ⇒
        val byRequest: By = byRequestAny.asInstanceOf[By]
        val requestedList = byToHeaps(byRequest)
        models.flatMap(model ⇒ {
          val heaps: List[By] = fieldToHeaps(lens.of(model))
          val intersectedHeap: List[By] = requestedList.intersect(heaps)
          heapsToSrcIds(lensName, intersectedHeap).map(srcId ⇒ srcId → model)
        }
        )
      case None ⇒
        Nil
    }

  }

  def heapsToSrcIds[By <: Product](
    lensName: List[String], heaps: List[By]
  ): List[SrcId] = {
    heaps.map(heap ⇒ {
      val metaListUUID = serializer.srcIdFromSrcIds(lensName)
      val rangeUUID = serializer.srcIdFromOrig(heap, heap.getClass.getName)
      val modelUUID = serializer.srcIdFromSeqMany(modelClass.getName)
      val srcId = serializer.srcIdFromSeqMany(modelUUID, metaListUUID, rangeUUID).toString
      srcId
    }
    )
  }

  private def rangerCaster[By <: Product, Field](
    byCl: Class[By], fieldCl: Class[Field]
  ): RangerWithCl[_, _] ⇒ RangerWithCl[By, Field] =
    _.asInstanceOf[RangerWithCl[By, Field]]

  def modelsToHeaps(
    models: Values[Model], node: IndexDirective[Model]
  ): Values[(SrcId, Model)] = {
    rangerRegistry.getByByIdUntyped(node.byAdapterId) match {
      case Some(ranger) ⇒
        modelsToHeapsInner(models, ranger.byCl, ranger.fieldCl, ranger, node)
      case None ⇒
        Nil
    }
  }

  private def modelsToHeapsInner[By <: Product, Field](
    models: Values[Model], byCl: Class[By], fieldCl: Class[Field], ranger: RangerWithCl[_ <: Product, _], node: IndexDirective[Model]
  ): Values[(SrcId, Model)] = {
    val rangerTyped: RangerWithCl[By, Field] = rangerCaster(byCl, fieldCl)(ranger)
    val (left, _): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = prepareRanger(byCl, fieldCl, rangerTyped, node.directive)
    modelsToHeapIds(byCl, fieldCl, models, node.lensName, left)
  }

  def modelToHeapsBy(
    models: Values[Model], nodeBy: IndexByDirective[Model]
  ): Values[(SrcId, Model)] = {
    rangerRegistry.getByByIdUntyped(nodeBy.byAdapterId) match {
      case Some(ranger) ⇒
        modelsToHeapsInnerBy(models, ranger.byCl, ranger.fieldCl, ranger, nodeBy)
      case None ⇒
        Nil
    }
  }

  private def modelsToHeapsInnerBy[By <: Product, Field](
    models: Values[Model], byCl: Class[By], fieldCl: Class[Field], ranger: RangerWithCl[_ <: Product, _], nodeBy: IndexByDirective[Model]
  ): Values[(SrcId, Model)] = {
    val rangerTyped: RangerWithCl[By, Field] = rangerCaster(byCl, fieldCl)(ranger)
    val (left, right): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = prepareRanger(byCl, fieldCl, rangerTyped, nodeBy.directive)
    modelsToHeapIdsBy(byCl, fieldCl, models, nodeBy.lensName, left, nodeBy.byNode, right)
  }

  def leafToHeapIds(
    prodCond: ProdCondition[_ <: Product, _], directives: Values[RangerDirective[Model]]
  ): List[SrcId] = {
    rangerRegistry.getByByCl(prodCond.by.getClass.getName) match {
      case Some(ranger) ⇒
        leafToHeapIdsInner(ranger.byCl, ranger.fieldCl, ranger, prodCond, directives.map(_.directive))
      case None ⇒
        Nil
    }
  }

  private def leafToHeapIdsInner[By <: Product, Field](
    byCl: Class[By], fieldCl: Class[Field], ranger: RangerWithCl[_ <: Product, _], prodCond: ProdCondition[_ <: Product, _], directives: Seq[AnyOrig]
  ): List[SrcId] = {
    val prodConditionTyped: ProdCondition[By, Model] = castProdCondition(byCl)(prodCond)
    val byClName: SrcId = byCl.getName
    val byAdapterId: Long = qAdapterRegistry.byName(byClName).id
    val directive: By = directives.collectFirst {
      case a if a.adapterId == byAdapterId ⇒ decode[By](qAdapterRegistry)(a)
    }.getOrElse(defaultModelRegistry.get[By](byClName).create(""))
    val typedRanger: RangerWithCl[By, Field] = rangerCaster(byCl, fieldCl)(ranger)
    val (_, right) = typedRanger.ranges(directive)
    val ranges: List[By] = right.apply(prodConditionTyped.by)
    val lensName = prodCond.metaList.collect { case a: NameMetaAttr ⇒ a.value }
    heapsToSrcIds(lensName, ranges)
  }

  private def castProdCondition[By <: Product](byCl: Class[By]): ProdCondition[_ <: Product, _] ⇒ ProdCondition[By, Model] =
    _.asInstanceOf[ProdCondition[By, Model]]
}

case class RangerDirective[Model <: Product](srcId: SrcId, directive: AnyOrig)

/*case class IndexNodeWithDirective[Model <: Product](
  srcId: SrcId,
  indexNode: IndexNodeRich[Model],
  directive: Option[RangerDirective[Model]]
)*/

case class IndexDirective[Model <: Product](
  srcId: SrcId,
  byAdapterId: Long,
  lensName: List[String],
  directive: Option[DecodedBy]
)

case class IndexByDirective[Model <: Product](
  srcId: SrcId,
  byAdapterId: Long,
  lensName: List[String],
  directive: Option[DecodedBy],
  byNode: DecodedBy
)

case class DynamicNeed[Model <: Product](requestId: SrcId)

case class DynamicCount[Model <: Product](heapId: SrcId, count: Int)

trait DynamicIndexSharedTypes {
  type DynamicIndexDirectiveAll = All
}

@assemble class HashSearchDynamicIndexAssemble[Model <: Product](
  modelCl: Class[Model],
  val modelId: Int,
  val rangerRegistry: HashSearchRangerRegistryApi,
  val defaultModelRegistry: DefaultModelRegistry,
  val qAdapterRegistry: QAdapterRegistry,
  val lensRegistryApi: LensRegistryApi,
  val serializer: SerializationUtils
)(val modelClass: Class[Model]) extends Assemble
  with HashSearchAssembleSharedKeys
  with HashSearchDynamicIndexAssembleUtils[Model]
  with DynamicIndexSharedTypes {
  type DynamicHeapId = SrcId
  type IndexNodeRichAll = All
  type IndexByNodeRichAll = All
  type LeafConditionId = SrcId

  // Mock DynamicIndexDirectiveAll if none defined
  def DynamicIndexDirectiveMock(
    directiveId: SrcId,
    firstborn: Each[Firstborn]
  ): Values[(DynamicIndexDirectiveAll, RangerDirective[Model])] =
    Nil

  // AllHeap
  def AllHeap(
    modelId: SrcId,
    model: Each[Model]
  ): Values[(DynamicHeapId, Model)] =
    (anyModelKey → model) :: Nil

  // START: If node.keepAllAlive
  def IndexNodeRichToIndexNodeAll(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]],
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexNodeRichAll, IndexDirective[Model])] =
    if (node.keepAllAlive) {
      val directive: Option[RangerDirective[Model]] = indexNodeDirectives.collectFirst {
        case a if a.directive.adapterId == node.indexNode.byAdapterId ⇒ a
      }
      (All → IndexDirective[Model](
        node.srcId,
        node.indexNode.byAdapterId,
        node.indexNode.lensName,
        directive.map(dir ⇒ decodeBy(dir.directive))
      )) :: Nil
    }
    else Nil

  def ModelToHeapIdByIndexNode(
    modelId: SrcId,
    models: Values[Model],
    @by[IndexNodeRichAll] node: Each[IndexDirective[Model]]
  ): Values[(DynamicHeapId, Model)] =
    modelsToHeaps(models, node)

  // END: If node.keepAllAlive

  // START: !If node.keepAllAlive
  def IndexNodeRichToIndexByNodeWithDirectiveAll(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]],
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexByNodeRichAll, IndexByDirective[Model])] =
    if (!node.keepAllAlive) {
      val directive = indexNodeDirectives.collectFirst {
        case a if a.directive.adapterId == node.indexNode.byAdapterId ⇒ a
      }
      val lensName = node.indexNode.lensName
      for {
        decoded ← node.indexByNodes.map(_.indexByNode.byInstance.get).map(decodeBy)
      } yield
      All → IndexByDirective[Model](
        node.srcId,
        node.indexNode.byAdapterId,
        lensName,
        directive.map(dir ⇒ decodeBy(dir.directive)),
        decoded
      )
    }
    else Nil

  def ModelToHeapIdByIndexByNode(
    modelId: SrcId,
    models: Values[Model],
    @by[IndexByNodeRichAll] nodeBy: Each[IndexByDirective[Model]]
  ): Values[(DynamicHeapId, Model)] =
    modelToHeapsBy(models, nodeBy)

  // END: !If node.keepAllAlive

  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]],
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]]
  ): Values[(DynamicHeapId, DynamicNeed[Model])] =
    leaf.condition match {
      case prodCond: ProdCondition[_, _] ⇒
        for {
          heapId ← leafToHeapIds(prodCond, indexNodeDirectives).distinct
        } yield heapId → DynamicNeed[Model](leaf.srcId)
      case AnyCondition() ⇒ (anyModelKey → DynamicNeed[Model](leaf.srcId)) :: Nil
    }

  def DynNeedToDynCountToRequest(
    heapId: SrcId,
    @by[DynamicHeapId] models: Values[Model],
    @by[DynamicHeapId] need: Each[DynamicNeed[Model]]
  ): Values[(LeafConditionId, DynamicCount[Model])] =
    (need.requestId → DynamicCount[Model](heapId, models.length)) :: Nil

  def DynCountsToCondEstimate(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]],
    @by[LeafConditionId] counts: Values[DynamicCount[Model]]
  ): Values[(SrcId, InnerConditionEstimate[Model])] =
    for {
      condEstimate ← cDynEstimate(leaf, counts)
    } yield {
      WithPK(condEstimate)
    }

  def DynHandleRequest(
    heapId: SrcId,
    @by[DynamicHeapId] @distinct models: Values[Model],
    @by[SharedHeapId] request: Each[InnerUnionList[Model]]
  ): Values[(SharedResponseId, ResponseModelList[Model])] = {
    val lines = for {
      line ← models.par
      if request.check(line)
    } yield line
    (request.srcId → ResponseModelList[Model](request.srcId + heapId, lines.toList)) :: Nil
  }


  /*def Test(
    modelId: SrcId,
    @by[DynamicHeapId] models: Values[Model]
  ): Values[(All, A)] = {
    (All → A(modelId, models.size)) :: Nil
  }

  def PrintTest(
    lusdf: SrcId,
    firstborn: Values[Firstborn],
    @by[All] lule: Values[A],
    @by[IndexByNodeRichAll] indexByNodeWithDirective: Values[IndexByNodeWithDirective[Model]]
  ): Values[(All, Model)] = {
    for {
      a ← lule
    } yield {
      PrintColored("", "w")(s"[HEAPS] ${a.srcId.slice(41, 80)}:${a.size}")
    }
    //PrintColored("b", "w")(s"${indexByNodeWithDirective.size}")
    PrintColored("", "w")(s"---------------------------------------")
    Nil
  }*/
}

case class A(srcId: SrcId, size: Int)
