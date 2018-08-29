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

  def createIndexDirective(
    node: IndexNodeRich[Model],
    indexNodeDirectives: Values[RangerDirective[Model, By, Field]],
    build: Boolean
  ): Values[(All, IndexDirective[Model, By, Field])] =
    lensRegistry.getOpt[Model, Field](node.indexNode.lensName) match {
      case Some(lens) ⇒
        val directiveOpt = indexNodeDirectives.collectFirst {
          case a if a.lensName == node.indexNode.lensName ⇒ a.directive
        }
        val ranges = ranger.ranges(directiveOpt.getOrElse(defaultBy))
        WithAll(
          IndexDirective[Model, By, Field](
            node.srcId,
            node.indexNode.byAdapterId,
            node.indexNode.lensName,
            directiveOpt,
            needBuild = build
          )(lens, ranges._1)
        ) :: Nil
      case None ⇒ Nil
    }

  def modelToIndexModel(
    model: Model, node: IndexDirective[Model, By, Field]
  ): Values[(String, IndexModel[Model, By, Field])] = {
    val modelSrcIdId = ToPrimaryKey(model)
    val srcId = generateId(modelSrcIdId :: byClassName :: node.lensName)
    (srcId → IndexModel[Model, By, Field](srcId, modelSrcIdId, node.lens.of(model), node.lensName)) :: Nil
  }

  def indexModelToHeaps(
    model: IndexModel[Model, By, Field],
    node: IndexDirective[Model, By, Field]
  ): Values[(String, IndexModel[Model, By, Field])] =
    if (node.needBuild) {
      val ranges = node.modelToHeaps(model.field)
      heapToSrcIds(node.lensName, ranges).map(srcId ⇒ srcId → model)
    }
    else Nil

  def indexModelToHeapsBy(
    model: IndexModel[Model, By, Field],
    node: IndexByDirective[Model, By, Field]
  ): Values[(String, IndexModel[Model, By, Field])] = {
    val ranges: List[By] = node.modelToHeaps(model.field)
    heapToSrcIds(node.lensName, ranges.filter(node.bySet.contains)).map(srcId ⇒ srcId → model)
  }

  def heapToSrcIds(
    lensName: List[String],
    ranges: List[By]
  ): List[SrcId] = {
    ranges.map(heap ⇒ {
      generateId(modelClassName :: heap :: lensName)
    }
    )
  }

  def generateId(
    in: List[Any]
  ): String = {
    val instance = new MurmurHash3()
    preHashing.calculateModelHash(in, instance)
    instance.getStringHash
  }

  def leafToHeapIds(
    lensName: List[String], prodCondition: ProdCondition[By, Model], directive: Option[By]
  ): List[SrcId] = {
    val (_, rqHandler) = ranger.ranges(directive.getOrElse(defaultBy))
    val heaps = rqHandler(prodCondition.by).distinct
    heapToSrcIds(lensName, heaps)
  }


  type InnerIndexModel[ModelType, ByType, FieldType] = SrcId

  type InnerDynamicHeapId[ModelType, ByType, FieldType] = SrcId

  type OuterDynamicHeapId = SrcId

  type IndexNodeDirectiveAll = All

  type LeafConditionId = SrcId
}

trait DynamicIndexSharedTypes {
  type DynamicIndexDirectiveAll = All
}

case class IndexModel[Model <: Product, By <: Product, Field](
  srcId: SrcId,
  modelSrcId: SrcId,
  field: Field,
  lensName: List[String]
)

case class RangerDirective[Model <: Product, By <: Product, Field](
  srcId: SrcId,
  lensName: List[String],
  directive: By
)

case class IndexDirective[Model <: Product, By <: Product, Field](
  srcId: SrcId,
  byAdapterId: Long,
  lensName: List[String],
  directive: Option[By],
  needBuild: Boolean
)(
  val lens: ProdLens[Model, Field],
  val modelToHeaps: Field ⇒ List[By]
)

case class IndexByDirective[Model <: Product, By <: Product, Field](
  srcId: SrcId,
  byAdapterId: Long,
  lensName: List[String],
  nodes: List[By],
  directive: By
)(
  val modelToHeaps: Field ⇒ List[By]
) {
  lazy val bySet: Set[By] = nodes.toSet
}

case class ModelNeed[Model <: Product, By <: Product, Field](
  modelSrcId: SrcId,
  heapSrcId: SrcId
)

case class DynamicNeed[Model <: Product](requestId: SrcId)

case class DynamicCount[Model <: Product](heapId: SrcId, count: Int)

@assemble class HashSearchDynamicIndexNew[Model <: Product, By <: Product, Field](
  modelCl: Class[Model],
  byCl: Class[By],
  fieldCl: Class[Field],
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
  ): Values[(DynamicIndexDirectiveAll, RangerDirective[Model, By, Field])] =
    Nil

  // Create IndexDirectives for static and indexModel build for dynamic
  def IndexNodeRichToIndexNode(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model, By, Field]],
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexNodeDirectiveAll, IndexDirective[Model, By, Field])] =
    if (byAdapterId == node.indexNode.byAdapterId)
      if (node.keepAllAlive)
        createIndexDirective(node, indexNodeDirectives, build = true)
      else if (node.indexByNodes.nonEmpty)
        createIndexDirective(node, indexNodeDirectives, build = false)
      else Nil
    else Nil

  // Create index directive for dynamics
  def IndexNodeRichToIndexByNode(
    indexNodeId: SrcId,
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model, By, Field]],
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexNodeDirectiveAll, IndexByDirective[Model, By, Field])] =
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
          IndexByDirective[Model, By, Field](
            nodeBy.srcId,
            node.indexNode.byAdapterId,
            node.indexNode.lensName,
            bys,
            directive
          )(funTuple._1)
        )
      }
    }
    else Nil

  // Create inner models
  def ModelToIndexModel(
    modelId: SrcId,
    model: Each[Model],
    @by[IndexNodeDirectiveAll] node: Each[IndexDirective[Model, By, Field]]
  ): Values[(InnerIndexModel[Model, By, Field], IndexModel[Model, By, Field])] =
    modelToIndexModel(model, node)

  // Index node to heaps
  def IndexModelToHeap(
    indexModelId: SrcId,
    @by[InnerIndexModel[Model, By, Field]] model: Each[IndexModel[Model, By, Field]],
    @by[IndexNodeDirectiveAll] node: Each[IndexDirective[Model, By, Field]]
  ): Values[(InnerDynamicHeapId[Model, By, Field], IndexModel[Model, By, Field])] =
    indexModelToHeaps(model, node)

  def IndexModelToHeapBy(
    indexModelId: SrcId,
    @by[InnerIndexModel[Model, By, Field]] model: Each[IndexModel[Model, By, Field]],
    @by[IndexNodeDirectiveAll] node: Each[IndexByDirective[Model, By, Field]]
  ): Values[(InnerDynamicHeapId[Model, By, Field], IndexModel[Model, By, Field])] =
    indexModelToHeapsBy(model, node)

  // end index node to heaps

  // Handle Requests
  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]],
    @by[DynamicIndexDirectiveAll] indexNodeDirectives: Values[RangerDirective[Model, By, Field]]
  ): Values[(InnerDynamicHeapId[Model, By, Field], DynamicNeed[Model])] =
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
    @by[InnerDynamicHeapId[Model, By, Field]] innerModels: Values[IndexModel[Model, By, Field]],
    @by[InnerDynamicHeapId[Model, By, Field]] needs: Values[DynamicNeed[Model]]
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
    @by[InnerDynamicHeapId[Model, By, Field]] @distinct innerModel: Each[IndexModel[Model, By, Field]]
  ): Values[(OuterDynamicHeapId, ModelNeed[Model, By, Field])] =
    WithPK(ModelNeed[Model, By, Field](innerModel.modelSrcId, heapId)) :: Nil

  def CreateHeap(
    modelId: SrcId,
    model: Each[Model],
    @by[OuterDynamicHeapId] @distinct need: Each[ModelNeed[Model, By, Field]]
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
