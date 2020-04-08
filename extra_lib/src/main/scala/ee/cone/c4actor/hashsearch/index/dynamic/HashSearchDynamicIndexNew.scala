package ee.cone.c4actor.hashsearch.index.dynamic

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.hashsearch.base._
import ee.cone.c4actor.hashsearch.rangers.{HashSearchRangerRegistry, HashSearchRangerRegistryApp, RangerWithCl}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, provide}
import ee.cone.c4proto._

trait HashSearchDynamicIndexAppBase

@c4("HashSearchDynamicIndexApp") final class HashSearchDynamicIndexAssembles(
  hashSearchDynamicIndexCommonFactory: HashSearchDynamicIndexCommonFactory,
  hashSearchDynamicIndexNewFactory: HashSearchDynamicIndexNewFactory,
  dynIndexModelProviders: List[DynamicIndexModelsProvider],
  hashSearchRangerRegistry: HashSearchRangerRegistry,
  qAdapterRegistry: QAdapterRegistry,
  lensRegistry: LensRegistry
) {
  @provide def assembles: Seq[Assemble] = {
    val models: List[ProductWithId[_ <: Product]] = dynIndexModelProviders.flatMap(_.values).distinct
    val rangerWiseAssemble: List[HashSearchDynamicIndexNew[_ <: Product, Product, Any]] = models.flatMap(model => getAssembles(model))
    val availableModels = rangerWiseAssemble.map(_.modelId)
    val modelOnlyAssembles: List[Assemble] = models.map { model =>
      hashSearchDynamicIndexCommonFactory.create(model.modelCl, model.modelCl, model.modelId)
    }.filter(id => availableModels.contains(id.modelId))
    rangerWiseAssemble ::: modelOnlyAssembles
  }

  def createAssemble[Model <: Product, By <: Product, Field](a: Class[Model], b: Class[By], c: Class[Field])(
    modelId: Int,
    byId: Long,
    ranger: RangerWithCl[_ <: Product, _]
  ): HashSearchDynamicIndexNew[Model, By, Field] =
    hashSearchDynamicIndexNewFactory.create[Model, By, Field](
      a,
      b,
      c,
      c,
      a,
      modelId,
      byId,
      ranger.asInstanceOf[RangerWithCl[By, Field]],
    )

  def getAssembles(model: ProductWithId[_ <: Product]): List[HashSearchDynamicIndexNew[_ <: Product, Product, Any]] = {
    (for {
      ranger <- hashSearchRangerRegistry.getAll
    } yield {
      val byCl = ranger.byCl
      val byId = qAdapterRegistry.byName(byCl.getName).id
      val fieldCl = ranger.fieldCl

      val lenses = lensRegistry.getByClasses(model.modelCl.getName, fieldCl.getName)
      if (lenses.nonEmpty)
        createAssemble(
          model.modelCl,
          byCl,
          fieldCl
        )(
          model.modelId,
          byId,
          ranger
        ) :: Nil
      else Nil
    }).flatten
  }
}

sealed trait HashSearchDynamicIndexNewUtils[Model <: Product, By <: Product, Field] extends HashSearchIdGeneration {

  lazy val murMurHash: MurmurHash3 = new MurmurHash3()

  def qAdapterRegistry: QAdapterRegistry

  def lensRegistry: LensRegistry

  def idGenUtil: IdGenUtil

  def modelFactory: ModelFactory

  def ranger: RangerWithCl[By, Field]

  def modelClass: Class[Model]

  lazy val modelClassName: String = modelClass.getName

  lazy val byClass: Class[By] = ranger.byCl

  def byAdapterId: Long

  lazy val byClassName: String = byClass.getName

  def fieldClass: Class[Field]

  lazy val defaultBy: By = modelFactory.create[By](byClassName)("")

  lazy val byAdapter: ProtoAdapter[Product] with HasId = qAdapterRegistry.byName(byClassName)

  def createIndexDirective(
    node: IndexNodeRich[Model],
    build: Boolean
  ): Values[(AbstractAll, IndexDirective[Model, By, Field])] =
    lensRegistry.getByCommonPrefix[Model, Field](node.indexNode.commonPrefix) match {
      case Some(lens) =>
        /*println(lens.metaList, byClassName, node.indexNode)*/
        val dir = if (node.directive.isDefined) node.directive.get.asInstanceOf[By] else defaultBy
        val ranges = ranger.ranges(dir)
        WithAll(
          IndexDirective[Model, By, Field](
            node.srcId,
            node.indexNode.commonPrefix,
            build
          )(lens.of, ranges._1)
        ) :: Nil
      case None => Nil
    }

  def modelToIndexModel(
    model: Model, node: IndexDirective[Model, By, Field]
  ): Values[(String, IndexModel[Model, By, Field])] = {
    val modelSrcIdId = ToPrimaryKey(model)
    val srcId = indexModelId(node.commonPrefix, modelSrcIdId)
    (srcId -> IndexModel[Model, By, Field](srcId, modelSrcIdId, node.of(model))) :: Nil
  }

  def indexModelToHeaps(
    model: IndexModel[Model, By, Field],
    node: IndexDirective[Model, By, Field]
  ): Values[(String, IndexModel[Model, By, Field])] =
    if (node.needBuild) {
      val ranges = node.modelToHeaps(model.field)
      ranges.map(heapId(node.commonPrefix, _)).map(srcId => srcId -> model)
    }
    else Nil

  def indexModelToHeapsBy(
    model: IndexModel[Model, By, Field],
    node: IndexByDirective[Model, By, Field]
  ): Values[(String, IndexModel[Model, By, Field])] = {
    val ranges = node.modelToHeaps(model.field)
    ranges.map(heapId(node.commonPrefix, _)).filter(node.heapIdsSet).map(srcId => srcId -> model)
  }

  type InnerIndexModel[ModelType, ByType, FieldType] = SrcId

  type InnerDynamicHeapId[ModelType, ByType, FieldType] = SrcId

  type OuterDynamicHeapId = SrcId

  type IndexNodeDirectiveAll = AbstractAll

  type LeafConditionId = SrcId
}

trait DynamicIndexSharedTypes {
  type DynamicIndexDirectiveAll = AbstractAll
}

case class IndexModel[Model <: Product, By <: Product, Field](
  indexModelId: SrcId,
  modelSrcId: SrcId,
  field: Field
) {
  override def toString: SrcId = s"IndexModel(imId=$indexModelId, mId=$modelSrcId, f=$field)"
}

case class IndexDirective[Model <: Product, By <: Product, Field](
  indexNodeId: SrcId,
  commonPrefix: String,
  needBuild: Boolean
)(
  val of: Model => Field,
  val modelToHeaps: Field => List[By]
)

case class IndexByDirective[Model <: Product, By <: Product, Field](
  leafId: SrcId,
  commonPrefix: String
)(
  val modelToHeaps: Field => List[By], val heapIdsSet: Set[String]
)

/*{
 lazy val bySet: Set[By] = nodes.toSet
}*/

case class ModelNeed[Model <: Product, By <: Product, Field](
  modelSrcId: SrcId,
  heapSrcId: SrcId
)

case class DynamicNeed[Model <: Product](requestId: SrcId)

case class DynamicCount[Model <: Product](heapId: SrcId, count: Int)

@c4multiAssemble("HashSearchDynamicIndexApp") class HashSearchDynamicIndexNewBase[Model <: Product, By <: Product, Field](
  modelCl: Class[Model],
  byCl: Class[By],
  fieldCl: Class[Field],
  val fieldClass: Class[Field],
  val modelClass: Class[Model],
  val modelId: Int,
  val byAdapterId: Long,
  val ranger: RangerWithCl[By, Field],
)(
  val qAdapterRegistry: QAdapterRegistry,
  val lensRegistry: LensRegistry,
  val idGenUtil: IdGenUtil,
  val modelFactory: ModelFactory
) extends AssembleName("HashSearchDynamicIndexNew", modelCl, byCl, fieldCl)
  with DynamicIndexSharedTypes
  with HashSearchAssembleSharedKeys
  with HashSearchDynamicIndexNewUtils[Model, By, Field] {

  // Create IndexDirectives for static and indexModel build for dynamic
  def IndexNodeRichToIndexNode(
    indexNodeId: SrcId,
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexNodeDirectiveAll, IndexDirective[Model, By, Field])] =
    if (byAdapterId == node.indexNode.byAdapterId)
      if (node.isStatic)
        createIndexDirective(node, build = true)
      else if (node.indexByNodes.nonEmpty)
        createIndexDirective(node, build = false)
      else Nil
    else Nil

  // Create index directive for dynamics
  def IndexNodeRichToIndexByNode(
    indexNodeId: SrcId,
    node: Each[IndexNodeRich[Model]]
  ): Values[(IndexNodeDirectiveAll, IndexByDirective[Model, By, Field])] =
    if (byAdapterId == node.indexNode.byAdapterId && !node.isStatic) {
      val directive = if (node.directive.isDefined) node.directive.get.asInstanceOf[By] else defaultBy
      val toHeaps = ranger.ranges(directive)._1
      for {
        nodeBy <- node.indexByNodes
        if nodeBy.isAlive
      } yield {
        WithAll(
          IndexByDirective[Model, By, Field](
            nodeBy.srcId,
            node.indexNode.commonPrefix
          )(toHeaps, nodeBy.heapIdsSet)
        )
      }
    }
    else Nil

  // Create inner models
  def ModelToIndexModel(
    modelId: SrcId,
    model: Each[Model],
    @byEq[IndexNodeDirectiveAll](All) node: Each[IndexDirective[Model, By, Field]]
  ): Values[(InnerIndexModel[Model, By, Field], IndexModel[Model, By, Field])] =
    modelToIndexModel(model, node)

  // Index node to heaps
  def IndexModelToHeap(
    indexModelId: SrcId,
    @by[InnerIndexModel[Model, By, Field]] model: Each[IndexModel[Model, By, Field]],
    @byEq[IndexNodeDirectiveAll](All) node: Each[IndexDirective[Model, By, Field]]
  ): Values[(InnerDynamicHeapId[Model, By, Field], IndexModel[Model, By, Field])] =
    indexModelToHeaps(model, node)

  def IndexModelToHeapBy(
    indexModelId: SrcId,
    @by[InnerIndexModel[Model, By, Field]] model: Each[IndexModel[Model, By, Field]],
    @byEq[IndexNodeDirectiveAll](All) node: Each[IndexByDirective[Model, By, Field]]
  ): Values[(InnerDynamicHeapId[Model, By, Field], IndexModel[Model, By, Field])] =
    indexModelToHeapsBy(model, node)

  // end index node to heaps

  // Handle Requests
  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leaf: Each[ProcessedLeaf[Model]]
  ): Values[(InnerDynamicHeapId[Model, By, Field], DynamicNeed[Model])] =
    if (leaf.preProcessed.byId == byAdapterId)
      for {
        heapId <- leaf.heapIds
        leafId <- leaf.originalLeafIds
      } yield heapId -> DynamicNeed[Model](leafId)
    else {
      Nil
    }


  def DynNeedToDynCountToRequest(
    heapId: SrcId,
    @by[InnerDynamicHeapId[Model, By, Field]] innerModels: Values[IndexModel[Model, By, Field]],
    @by[InnerDynamicHeapId[Model, By, Field]] needs: Values[DynamicNeed[Model]]
  ): Values[(LeafConditionId, DynamicCount[Model])] = {
    val modelsSize = innerModels.size
    for {
      need <- needs
    } yield
      need.requestId -> DynamicCount[Model](heapId, modelsSize)
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
    (need.heapSrcId -> model) :: Nil
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

@c4multiAssemble("HashSearchDynamicIndexApp") class HashSearchDynamicIndexCommonBase[Model <: Product](
  modelCl: Class[Model],
  val modelClass: Class[_],
  val modelId: Int
)(
  val idGenUtil: IdGenUtil,
  indexUtil: IndexUtil
) extends AssembleName("HashSearchDynamicIndexCommon", modelCl) with DynIndexCommonUtils[Model] with HashSearchAssembleSharedKeys {
  type InnerIndexModel = SrcId
  type OuterDynamicHeapId = SrcId
  type IndexNodeDirectiveAll = AbstractAll
  type LeafConditionId = SrcId
  type AllHeapId = SrcId

  // AllHeap
  def AllHeap(
    modelId: SrcId,
    model: Each[Model]
  ): Values[(AllHeapId, Model)] =
    (anyModelKey -> model) :: Nil

  def AllHeapCreate(
    modelId: SrcId,
    @by[AllHeapId] model: Each[Model]
  ): Values[(OuterDynamicHeapId, Model)] =
    (anyModelKey -> model) :: Nil

  def RequestToDynNeedToHeap(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]]
  ): Values[(AllHeapId, DynamicNeed[Model])] =
    leaf.condition match {
      case AnyCondition() => (anyModelKey -> DynamicNeed[Model](leaf.srcId)) :: Nil
      case _ => Nil
    }

  def DynNeedToDynCountToRequest(
    heapId: SrcId,
    @by[AllHeapId] models: Values[Model],
    @by[AllHeapId] needs: Values[DynamicNeed[Model]]
  ): Values[(LeafConditionId, DynamicCount[Model])] =
    if (heapId == anyModelKey) {
      val size = models.size
      for {
        need <- needs
      } yield need.requestId -> DynamicCount[Model](heapId, size)
    } else Nil


  def DynCountsToCondEstimate(
    leafCondId: SrcId,
    leaf: Each[InnerLeaf[Model]],
    @by[LeafConditionId] counts: Values[DynamicCount[Model]]
  ): Values[(SrcId, InnerConditionEstimate[Model])] = {
    for {
      condEstimate <- cDynEstimate(leaf, counts)
    } yield {
      WithPK(condEstimate)
    }
  }

  def DynHandleRequest(
    heapId: SrcId,
    @by[OuterDynamicHeapId] @distinct models: Values[Model],
    @by[SharedHeapId] request: Each[InnerUnionList[Model]]
  ): Values[(SharedResponseId, ResponseModelList[Model])] = {
    val lines = indexUtil.mayBePar(models).filter(request.check)
    (request.srcId -> ResponseModelList[Model](request.srcId + heapId, lines.toList)) :: Nil
  }
}
