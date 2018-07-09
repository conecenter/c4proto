package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.base._
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryApi, HashSearchRangerRegistryApp, RangerWithCl}
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
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


// By is Orig:
//   lens is obligatory
//   ranger is optional
sealed trait HashSearchDynamicIndexAssembleUtils[Model <: Product] {
  def modelClass: Class[Model]

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
    anyObject: Option[AnyOrig]
  ): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = {
    val directive: By = anyObject.map(decode[By](qAdapterRegistry, byCl)(_))
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
        models.par.flatMap(model ⇒ {
          val heaps: List[By] = fieldToHeaps(lens.of(model))
          heapsToSrcIds(lensName, heaps).map(srcId ⇒ srcId → model)
        }
        ).to[Values]
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
    byRequestAny: AnyOrig,
    byToHeaps: PartialFunction[Product, List[By]]
  ): Values[(SrcId, Model)] = {
    val lensOpt: Option[ProdLens[Model, Field]] = lensRegistryApi.getOpt[Model, Field](lensName)
    lensOpt match {
      case Some(lens) ⇒
        val byRequest: By = decode[By](qAdapterRegistry)(byRequestAny)
        val requestedList = byToHeaps.apply(byRequest)
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

      /*val metaListUUID = serializer.uuidFromSeq(lensName.map(str ⇒ serializer.uuid(str)))
      val rangeUUID = serializer.uuidFromOrig(heap, heap.getClass.getName)
      val modelUUID = serializer.uuid(modelClass.getName)
      val srcId = serializer.uuidFromSeq(modelUUID, metaListUUID, rangeUUID).toString*/
      s"${modelClass.getName}$lensName$heap$srcId" // TODO replace with "srcId"
    }
    )
  }

  private def rangerCaster[By <: Product, Field](
    byCl: Class[By], fieldCl: Class[Field]
  ): RangerWithCl[_, _] ⇒ RangerWithCl[By, Field] =
    _.asInstanceOf[RangerWithCl[By, Field]]

  def modelsToHeaps(
    models: Values[Model], node: IndexNodeWithDirective[Model]
  ): Values[(SrcId, Model)] = {
    rangerRegistry.getByByIdUntyped(node.indexNode.indexNode.byAdapterId) match {
      case Some(ranger) ⇒
        modelsToHeapsInner(models, ranger.byCl, ranger.fieldCl, ranger, node)
      case None ⇒
        Nil
    }
  }

  private def modelsToHeapsInner[By <: Product, Field](
    models: Values[Model], byCl: Class[By], fieldCl: Class[Field], ranger: RangerWithCl[_ <: Product, _], node: IndexNodeWithDirective[Model]
  ): Values[(SrcId, Model)] = {
    val rangerTyped: RangerWithCl[By, Field] = rangerCaster(byCl, fieldCl)(ranger)
    val (left, _): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = prepareRanger(byCl, fieldCl, rangerTyped, node.directive.map(_.directive))
    modelsToHeapIds(byCl, fieldCl, models, node.indexNode.indexNode.lensName, left)
  }

  def modelToHeapsBy(
    models: Values[Model], nodeBy: IndexByNodeWithDirective[Model], debug: Boolean = false
  ): Values[(SrcId, Model)] = {
    rangerRegistry.getByByIdUntyped(nodeBy.indexNode.indexByNode.byInstance.get.adapterId) match {
      case Some(ranger) ⇒
        modelsToHeapsInnerBy(models, ranger.byCl, ranger.fieldCl, ranger, nodeBy)
      case None ⇒
        Nil
    }
  }

  private def modelsToHeapsInnerBy[By <: Product, Field](
    models: Values[Model], byCl: Class[By], fieldCl: Class[Field], ranger: RangerWithCl[_ <: Product, _], nodeBy: IndexByNodeWithDirective[Model]
  ): Values[(SrcId, Model)] = {
    val rangerTyped: RangerWithCl[By, Field] = rangerCaster(byCl, fieldCl)(ranger)
    val (left, right): (Field ⇒ List[By], PartialFunction[Product, List[By]]) = prepareRanger(byCl, fieldCl, rangerTyped, nodeBy.directive.map(_.directive))
    modelsToHeapIdsBy(byCl, fieldCl, models, nodeBy.lensName, left, nodeBy.indexNode.indexByNode.byInstance.get, right)
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
    firstborn: Values[Firstborn]
  ): Values[(DynamicIndexDirectiveAll, RangerDirective[Model])] =
    Nil

  // START: If node.keepAllAlive
  def IndexNodeRichToIndexNodeAll(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]], // TODO forEach
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
    @by[IndexNodeRichAll] indexNodeWithDirective: Values[IndexNodeWithDirective[Model]] // TODO forEach
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
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]], // TODO forEach
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
  ): Values[(DynamicHeapId, Model)] = {
    (for {
      nodeBy ← indexByNodeWithDirective
    } yield {
      modelToHeapsBy(models, nodeBy, modelId == "100")
    }).flatten
  }

  // END: !If node.keepAllAlive

  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leafConditions: Values[InnerLeaf[Model]],
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model]] // TODO forEach
  ): Values[(DynamicHeapId, DynamicNeed[Model])] =
    for {
      leaf ← leafConditions
      if leaf.condition.isInstanceOf[ProdCondition[_ <: Product, _]]
      heapId ← leafToHeapIds(leaf.condition.asInstanceOf[ProdCondition[_ <: Product, _]], indexNodeDirectives)
    } yield heapId → DynamicNeed[Model](leaf.srcId)

  def DynNeedToDynCountToRequest(
    heapId: SrcId,
    @by[DynamicHeapId] models: Values[Model],
    @by[DynamicHeapId] needs: Values[DynamicNeed[Model]]
  ): Values[(LeafConditionId, DynamicCount[Model])] =
    for {
      need ← needs
    } yield need.requestId → DynamicCount[Model](heapId, models.length)

  def DynCountsToCondEstimate(
    leafCondId: SrcId,
    leafConditions: Values[InnerLeaf[Model]],
    @by[LeafConditionId] counts: Values[DynamicCount[Model]] // TODO add Index*Node here or maybeNot
  ): Values[(SrcId, InnerConditionEstimate[Model])] =
    for {
      leaf ← leafConditions
      condEstimate ← cDynEstimate(leaf, counts)
    } yield {
      WithPK(condEstimate)
    }

  def DynHandleRequest(
    heapId: SrcId,
    @by[DynamicHeapId] models: Values[Model],
    @by[SharedHeapId] requests: Values[InnerUnionList[Model]]
  ): Values[(SharedResponseId, ResponseModelList[Model])] =
    (for {
      request ← requests.par
    } yield {
      val lines = for {
        line ← models.par
        if request.check(line)
      } yield line
      request.srcId → ResponseModelList[Model](request.srcId + heapId, lines.toList)
    }).to[Values]


  /*def Test(
    modelId: SrcId,
    @by[DynamicHeapId] models: Values[Model]
  ): Values[(All, A)] = {
    Seq(All → A(modelId, models.size)).to[Values]
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
    PrintColored("b","w")(s"${indexByNodeWithDirective.size}")
    PrintColored("", "w")(s"---------------------------------------")
    Nil
  }*/
}

case class A(srcId: SrcId, size: Int)
