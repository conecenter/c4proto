package ee.cone.c4actor.hashsearch.index.dynamic

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.base._
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistryApp, RangerWithCl}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{HasId, ToByteString}

trait HashSearchDynamicIndexApp
  extends AssemblesApp
    with DynamicIndexModelsApp
    with QAdapterRegistryApp
    with LensRegistryApp
    with HashSearchRangerRegistryApp
    with IdGenUtilApp
    with DefaultModelRegistryApp {
  override def assembles: List[Assemble] = {
    val models: List[ProductWithId[_ <: Product]] = dynIndexModels.distinct


    val rangerWiseAssemble: List[HashSearchDynamicIndexNew[_ <: Product, Product, Any]] = models.flatMap(model ⇒ getAssembles(model))
    val avalibeModels = rangerWiseAssemble.map(_.modelId)
    val modelOnlyAssembles: List[Assemble] = models.map { model ⇒ new HashSearchDynamicIndexCommon(model.modelCl, model.modelCl, model.modelId, idGenUtil) }.filter(id ⇒ avalibeModels.contains(id.modelId))
    rangerWiseAssemble ::: modelOnlyAssembles ::: super.assembles
  }

  def getAssembles(model: ProductWithId[_ <: Product]): List[HashSearchDynamicIndexNew[_ <: Product, Product, Any]] = {
    (for {
      ranger ← hashSearchRangerRegistry.getAll
    } yield {
      val byCl = ranger.byCl
      val byId = qAdapterRegistry.byName(byCl.getName).id
      val fieldCl = ranger.fieldCl

      val lenses = lensRegistry.getByClasses(model.modelCl.getName, fieldCl.getName)
      if (lenses.nonEmpty)
        new HashSearchDynamicIndexNew(
          model.modelCl,
          byCl,
          fieldCl,
          model.modelCl,
          model.modelId,
          byId,
          qAdapterRegistry,
          lensRegistry,
          idGenUtil,
          ranger,
          defaultModelRegistry
        ) :: Nil
      else Nil
    }).flatten
  }
}

sealed trait HashSearchDynamicIndexNewUtils[Model <: Product, By <: Product, Field] {

  val murMurHash: MurmurHash3 = new MurmurHash3()

  lazy val preHashing: PreHashingMurMur3 = PreHashingMurMur3()

  def qAdapterRegistry: QAdapterRegistry

  def lensRegistry: LensRegistryApi

  def idGenUtil: IdGenUtil

  def defaultModelRegistry: DefaultModelRegistry

  def ranger: RangerWithCl[By, Field]

  def modelClass: Class[Model]

  lazy val modelClassName: String = modelClass.getName

  lazy val byClass: Class[By] = ranger.byCl

  lazy val byClassName: String = byClass.getName

  def fieldClass: Class[Field]

  lazy val defaultBy: By = defaultModelRegistry.get[By](byClassName).create("")

  lazy val byAdapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(byClassName)

  def createIndexNode(
    node: IndexNodeRich[Model],
    indexNodeDirectives: Values[RangerDirective[Model, By]],
    build: Boolean
  ): Values[(All, IndexDirective[Model, By])] =
    WithAll(
      IndexDirective[Model, By](
        node.srcId,
        node.indexNode.byAdapterId,
        node.indexNode.lensName,
        indexNodeDirectives.collectFirst { case a if a.lensName == node.indexNode.lensName ⇒ a.directive },
        needBuild = build
      )
    ) :: Nil

  def modelToIndexModel(
    model: Model, node: IndexDirective[Model, By]
  ): Values[(String, IndexModel[Model, By])] =
    lensRegistry.getOpt[Model, Field](node.lensName) match {
      case Some(lens) ⇒
        val modelSrcIdId = ToPrimaryKey(model)
        val srcId = idGenUtil.srcIdFromStrings(modelSrcIdId :: byClassName :: node.lensName: _*)
        (srcId → IndexModel[Model, By](srcId, modelSrcIdId, lens.of(model), node.lensName)) :: Nil
      case None ⇒
        Nil
    }

  def modelToIndexModelBy(
    model: Model,
    nodeBy: IndexByDirective[Model, By]
  ): Values[(String, IndexModel[Model, By])] =
    lensRegistry.getOpt[Model, Field](nodeBy.lensName) match {
      case Some(lens) ⇒
        val modelSrcIdId = ToPrimaryKey(model)
        val srcId = idGenUtil.srcIdFromStrings(modelSrcIdId :: nodeBy.lensName: _*)
        (srcId → IndexModel[Model, By](srcId, modelSrcIdId, lens.of(model), nodeBy.lensName)) :: Nil
      case None ⇒
        Nil
    }

  def indexModelToHeaps(
    model: IndexModel[Model, By],
    node: IndexDirective[Model, By]
  ): Values[(String, IndexModel[Model, By])] =
    if (node.needBuild) {
      val (modelToHeaps, _) = ranger.ranges(node.directive.getOrElse(defaultBy))
      val field = model.field.asInstanceOf[Field]
      val ranges = modelToHeaps(field)
      heapToSrcIds(node.lensName, ranges).map(srcId ⇒ srcId → model)
    }
    else Nil

  def indexModelToHeapsBy(
    model: IndexModel[Model, By],
    node: IndexByDirective[Model, By]
  ): Values[(String, IndexModel[Model, By])] = {
    val funTuple = ranger.ranges(node.directive)
    val field = model.field.asInstanceOf[Field]
    val ranges: List[By] = funTuple._1(field)
    heapToSrcIds(node.lensName, ranges.filter(node.bySet.contains)).map(srcId ⇒ srcId → model)
  }

  def heapToSrcIds(
    lensName: List[String],
    ranges: List[By]
  ): List[SrcId] = {
    ranges.map(heap ⇒ {
      val instance = new MurmurHash3()
      preHashing.calculateModelHash(modelClassName :: heap :: lensName, instance)
      instance.getStringHash
    }
    )
  }

  def leafToHeapIds(
    lensName: List[String], prodCondition: ProdCondition[By, Model], directive: Option[By]
  ): List[SrcId] = {
    val (_, rqHandler) = ranger.ranges(directive.getOrElse(defaultBy))
    val heaps = rqHandler(prodCondition.by).distinct
    heapToSrcIds(lensName, heaps)
  }


  type InnerIndexModel[ModelType, ByType] = SrcId

  type InnerDynamicHeapId[ModelType, ByType] = SrcId

  type OuterDynamicHeapId = SrcId

  type IndexNodeDirectiveAll = All

  type LeafConditionId = SrcId
}

trait DynamicIndexSharedTypes {
  type DynamicIndexDirectiveAll = All
}

case class IndexModel[Model <: Product, By <: Product](
  srcId: SrcId,
  modelSrcId: SrcId,
  field: Any,
  lensName: List[String]
)

case class RangerDirective[Model <: Product, By <: Product](
  srcId: SrcId,
  lensName: List[String],
  directive: By
)

case class IndexDirective[Model <: Product, By <: Product](
  srcId: SrcId,
  byAdapterId: Long,
  lensName: List[String],
  directive: Option[By],
  needBuild: Boolean
)

case class IndexByDirective[Model <: Product, By <: Product](
  srcId: SrcId,
  byAdapterId: Long,
  lensName: List[String],
  nodes: List[By],
  directive: By
) {
  lazy val bySet: Set[By] = nodes.toSet
}

case class ModelNeed[Model <: Product, By <: Product](
  modelSrcId: SrcId,
  heapSrcId: SrcId
)

case class DynamicNeed[Model <: Product](requestId: SrcId)

case class DynamicCount[Model <: Product](heapId: SrcId, count: Int)

@assemble class HashSearchDynamicIndexNew[Model <: Product, By <: Product, Field](
  modelCl: Class[Model],
  byCl: Class[By],
  val fieldClass: Class[Field],
  val modelClass: Class[Model],
  val modelId: Int,
  val byAdapterId: Long,
  val qAdapterRegistry: QAdapterRegistry,
  val lensRegistry: LensRegistryApi,
  val idGenUtil: IdGenUtil,
  val ranger: RangerWithCl[By, Field],
  val defaultModelRegistry: DefaultModelRegistry
) extends Assemble
  with DynamicIndexSharedTypes
  with HashSearchAssembleSharedKeys
  with HashSearchDynamicIndexNewUtils[Model, By, Field] {


  // Mock DynamicIndexDirectiveAll if none defined
  def DynamicIndexDirectiveMock(
    directiveId: SrcId,
    firstborn: Each[Firstborn]
  ): Values[(DynamicIndexDirectiveAll, RangerDirective[Model, By])] =
    Nil

  // Create IndexDirectives for static and indexModel build for dynamic
  def IndexNodeRichToIndexNode(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model, By]],
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexNodeDirectiveAll, IndexDirective[Model, By])] =
    if (byAdapterId == node.indexNode.byAdapterId)
      if (node.keepAllAlive)
        createIndexNode(node, indexNodeDirectives, build = true)
      else if (node.indexByNodes.nonEmpty)
        createIndexNode(node, indexNodeDirectives, build = false)
      else Nil
    else Nil

  // Create index directive for dynamics
  def IndexNodeRichToIndexByNode(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model, By]],
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexNodeDirectiveAll, IndexByDirective[Model, By])] =
    if (byAdapterId == node.indexNode.byAdapterId && !node.keepAllAlive) {
      val directiveOpt = indexNodeDirectives.collectFirst { case a if a.lensName == node.indexNode.lensName ⇒ a.directive }
      for {
        nodeBy ← node.indexByNodes
        if nodeBy.isAlive
      } yield {
        val directive = directiveOpt.getOrElse(defaultBy)
        val funTuple = ranger.ranges(directive)
        val bys = funTuple._2(nodeBy.indexByNode.byInstance.map(AnyAdapter.decode[By](qAdapterRegistry)).get)
        WithAll(
          IndexByDirective[Model, By](
            nodeBy.srcId,
            node.indexNode.byAdapterId,
            node.indexNode.lensName,
            bys,
            directive
          )
        )
      }
    }
    else Nil

  // Create inner models
  def ModelToIndexModel(
    modelId: SrcId,
    model: Each[Model],
    @by[IndexNodeDirectiveAll] node: Each[IndexDirective[Model, By]]
  ): Values[(InnerIndexModel[Model, By], IndexModel[Model, By])] =
    modelToIndexModel(model, node)

  // Index node to heaps
  def IndexModelToHeap(
    indexModelId: SrcId,
    @by[InnerIndexModel[Model, By]] model: Each[IndexModel[Model, By]],
    @by[IndexNodeDirectiveAll] node: Each[IndexDirective[Model, By]]
  ): Values[(InnerDynamicHeapId[Model, By], IndexModel[Model, By])] =
    indexModelToHeaps(model, node)

  def IndexModelToHeapBy(
    indexModelId: SrcId,
    @by[InnerIndexModel[Model, By]] model: Each[IndexModel[Model, By]],
    @by[IndexNodeDirectiveAll] node: Each[IndexByDirective[Model, By]]
  ): Values[(InnerDynamicHeapId[Model, By], IndexModel[Model, By])] =
    indexModelToHeapsBy(model, node)

  // end index node to heaps

  // Handle Requests
  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]],
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model, By]]
  ): Values[(InnerDynamicHeapId[Model, By], DynamicNeed[Model])] =
    leaf.condition match {
      case prodCond: ProdCondition[By, Model] if prodCond.by.getClass.getName == byClassName ⇒
        val lensName = prodCond.metaList.collect { case a: NameMetaAttr ⇒ a.value }
        for {
          heapId ← leafToHeapIds(lensName, prodCond, indexNodeDirectives.collectFirst { case a if a.lensName == lensName ⇒ a.directive }).distinct
        } yield heapId → DynamicNeed[Model](leaf.srcId)
      case _ ⇒ Nil
    }

  def DynNeedToDynCountToRequest(
    heapId: SrcId,
    @by[InnerDynamicHeapId[Model, By]] innerModels: Values[IndexModel[Model, By]],
    @by[InnerDynamicHeapId[Model, By]] needs: Values[DynamicNeed[Model]]
  ): Values[(LeafConditionId, DynamicCount[Model])] = {
    val modelsSize = innerModels.size
    for {
      need ← needs
    } yield
      need.requestId → DynamicCount[Model](heapId, modelsSize)
  }

  def SparkOuterHeap(
    heapId: SrcId,
    @by[SharedHeapId] request: Each[InnerUnionList[Model]],
    @by[InnerDynamicHeapId[Model, By]] @distinct innerModel: Each[IndexModel[Model, By]]
  ): Values[(OuterDynamicHeapId, ModelNeed[Model, By])] =
    WithPK(ModelNeed[Model, By](innerModel.modelSrcId, heapId)) :: Nil

  def CreateHeap(
    modelId: SrcId,
    model: Each[Model],
    @by[OuterDynamicHeapId] @distinct need: Each[ModelNeed[Model, By]]
  ): Values[(OuterDynamicHeapId, Model)] =
    (need.heapSrcId → model) :: Nil
}

sealed trait DynIndexCommonUtils[Model <: Product] {
  def idGenUtil: IdGenUtil

  def modelClass: Class[_]

  def modelId: Int

  lazy val anyModelKey: SrcId = idGenUtil.srcIdFromStrings(modelClass.getName, modelId.toString)

  def cDynEstimate(cond: InnerLeaf[Model], priorities: Values[DynamicCount[Model]]): Values[InnerConditionEstimate[Model]] = {
    val priorPrep = priorities.distinct
    if (priorPrep.nonEmpty)
      InnerConditionEstimate[Model](cond.srcId, Log2Pow2(priorPrep.map(_.count).sum), priorPrep.map(_.heapId).toList) :: Nil
    else
      Nil
  }
}

@assemble class HashSearchDynamicIndexCommon[Model <: Product](
  modelCl: Class[Model],
  val modelClass: Class[_],
  val modelId: Int,
  val idGenUtil: IdGenUtil
) extends Assemble with DynIndexCommonUtils[Model] with HashSearchAssembleSharedKeys {
  type InnerIndexModel = SrcId
  type OuterDynamicHeapId = SrcId
  type IndexNodeDirectiveAll = All
  type LeafConditionId = SrcId
  type AllHeapId = SrcId

  // AllHeap
  def AllHeap(
    modelId: SrcId,
    model: Each[Model]
  ): Values[(AllHeapId, Model)] =
    (anyModelKey → model) :: Nil

  def AllHeapCreate(
    modelId: SrcId,
    @by[AllHeapId] model: Each[Model]
  ): Values[(OuterDynamicHeapId, Model)] =
    (anyModelKey → model) :: Nil

  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]]
  ): Values[(AllHeapId, DynamicNeed[Model])] =
    leaf.condition match {
      case AnyCondition() ⇒ (anyModelKey → DynamicNeed[Model](leaf.srcId)) :: Nil
      case _ ⇒ Nil
    }

  def DynNeedToDynCountToRequest(
    heapId: SrcId,
    @by[AllHeapId] models: Values[Model],
    @by[AllHeapId] needs: Values[DynamicNeed[Model]]
  ): Values[(LeafConditionId, DynamicCount[Model])] =
    if (heapId == anyModelKey) {
      val size = models.size
      for {
        need ← needs
      } yield need.requestId → DynamicCount[Model](heapId, size)
    } else Nil


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
    @by[OuterDynamicHeapId] @distinct models: Values[Model],
    @by[SharedHeapId] request: Each[InnerUnionList[Model]]
  ): Values[(SharedResponseId, ResponseModelList[Model])] = {
    val lines = for {
      line ← models.par
      if request.check(line)
    } yield line
    (request.srcId → ResponseModelList[Model](request.srcId + heapId, lines.toList)) :: Nil
  }
}
