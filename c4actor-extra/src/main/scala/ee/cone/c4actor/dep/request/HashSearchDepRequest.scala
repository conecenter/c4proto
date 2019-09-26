package ee.cone.c4actor.dep.request

import ee.cone.c4actor.ArgTypes.LazyOption
import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{N_DepConditionAny, N_DepConditionIntersect, N_DepConditionLeaf, N_DepConditionUnion, N_HashSearchDepRequest}
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocolBase.ProtoDepCondition
import ee.cone.c4actor.utils.{GeneralizedOrigRegistry, GeneralizedOrigRegistryApi}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4proto._
import okio.ByteString

trait HashSearchRequestApp extends AssemblesApp with ProtocolsApp with GeneralizedOrigRegistryApi with DepResponseFactoryApp {
  override def assembles: List[Assemble] = leafRegistry.getModelsList.map(model ⇒ new HSDepRequestAssemble(hsDepRequestHandler, model, depResponseFactory)) ::: super.assembles

  override def protocols: List[Protocol] = HashSearchDepRequestProtocol :: super.protocols

  def qAdapterRegistry: QAdapterRegistry

  def leafRegistry: LeafRegistry

  def modelConditionFactory: ModelConditionFactory[Unit]

  def hsDepRequestHandler: HashSearchDepRequestHandler = HashSearchDepRequestHandler(leafRegistry, modelConditionFactory, generalizedOrigRegistry, qAdapterRegistry)
}

case class HashSearchDepRqWrap(srcId: String, request: N_HashSearchDepRequest, modelCl: String)

@assemble class HSDepRequestAssembleBase[Model <: Product](hsDepRequestHandler: HashSearchDepRequestHandler, model: Class[Model], util: DepResponseFactory)
  extends AssembleName("HSDepRequestAssemble", model) {

  def HSDepRequestWithSrcToItemSrcId(
    key: SrcId,
    rq: Each[DepInnerRequest]
  ): Values[(SrcId, Request[Model])] =
    rq.request match {
      case hsRq: N_HashSearchDepRequest if hsRq.modelName == model.getName =>
        List(WithPK(HashSearch.Request(rq.srcId, hsDepRequestHandler.handle(hsRq).asInstanceOf[Condition[Model]])))
      case _ => Nil
    }

  def HSResponseGrab(
    key: SrcId,
    resp: Each[Response[Model]],
    rq: Each[DepInnerRequest]
  ): Values[(SrcId, DepResponse)] =
    rq.request match {
      case innRequest: N_HashSearchDepRequest if innRequest.modelName == model.getName => List(WithPK(util.wrapRaw(rq, resp.linesHashed.asInstanceOf[PreHashed[Option[_]]])))
      case _ => Nil
    }
}

trait LeafRegistryApp {
  def leafs: List[LeafInfoHolder[_ <: Product, _ <: Product, _]] = Nil
}

trait LeafRegistryMix extends LeafRegistryApp {
  lazy val leafRegistryImpl = LeafRegistryImpl(leafs, leafs.map(_.modelCl))

  def leafRegistry: LeafRegistry = leafRegistryImpl
}

trait LeafRegistry {
  def getLeaf(modelCl: String, byCl: String, lensName: String): LeafInfoHolder[_ <: Product, _ <: Product, _]

  def getModelCl(modelClName: String): Class[_ <: Product]

  def getModelsList: List[Class[_ <: Product]]
}

case class LeafInfoHolder[Model <: Product, By <: Product, Field](
  lens: ProdLens[Model, Field],
  byOptions: List[AbstractMetaAttr],
  check: ConditionCheck[By, Field],

  modelCl: Class[Model],
  byCl: Class[By],
  fieldCl: Class[Field]
)


case class LeafRegistryImpl(
  leafList: List[LeafInfoHolder[_ <: Product, _ <: Product, _]],
  models: List[Class[_ <: Product]]
) extends LeafRegistry {

  private lazy val leafMap: Map[(String, String, String), LeafInfoHolder[_ <: Product, _ <: Product, _]] =
    leafList.map(leaf ⇒ (leaf.modelCl.getName, leaf.byCl.getName, leaf.lens.metaList.collect { case NameMetaAttr(v) ⇒ v }.head) → leaf).toMap

  private lazy val modelMap: Map[String, Class[_ <: Product]] = models.map(cl ⇒ cl.getName → cl).distinct.toMap[String, Class[_ <: Product]]

  def getLeaf(modelCl: String, byCl: String, lensName: String): LeafInfoHolder[_ <: Product, _ <: Product, _] = leafMap((modelCl, byCl, lensName))

  def getModelCl(modelClName: String): Class[_ <: Product] = modelMap(modelClName)

  def getModelsList: List[Class[_ <: Product]] = models.distinct
}

case class HashSearchDepRequestHandler(leafs: LeafRegistry, condFactory: ModelConditionFactory[Unit], generalizedOrigRegistry: GeneralizedOrigRegistry, qAdapterRegistry: QAdapterRegistry) {

  def handle: N_HashSearchDepRequest => Condition[_] = request ⇒
    parseCondition(
      request.condition.get, condFactory.ofWithCl(
        leafs.getModelCl(request.modelName)
      )
    )


  private def parseCondition[Model](condition: ProtoDepCondition, factory: ModelConditionFactory[Model]): Condition[Model] = {
    condition match {
      case inter: N_DepConditionIntersect ⇒ factory.intersect(parseCondition(inter.condLeft.get, factory), parseCondition(inter.condRight.get, factory))
      case union: N_DepConditionUnion ⇒ factory.union(parseCondition(union.condLeft.get, factory), parseCondition(union.condRight.get, factory))
      case _: N_DepConditionAny ⇒ factory.any
      case leaf: N_DepConditionLeaf ⇒
        val leafInfo: LeafInfoHolder[_ <: Product, _ <: Product, _] = leafs.getLeaf(leaf.modelClass, leaf.byClName, leaf.lensName)
        makeLeaf(leafInfo.modelCl, leafInfo.byCl, leafInfo.fieldCl)(leafInfo, leaf.value).asInstanceOf[Condition[Model]]
      case _ ⇒ throw new Exception("Not implemented yet: parseBy(by:By)")
    }
  }

  private def caster[Model <: Product, By <: Product, Field](m: Class[Model], b: Class[By], f: Class[Field]): LeafInfoHolder[_, _, _] ⇒ LeafInfoHolder[Model, By, Field] =
    _.asInstanceOf[LeafInfoHolder[Model, By, Field]]

  private def makeLeaf[Model <: Product, By <: Product, Field](modelCl: Class[Model], byClass: Class[By], fieldCl: Class[Field]):
  (LeafInfoHolder[_, _, _], ByteString) ⇒ ProdConditionImpl[By, Model, Field] = (leafInfo, by) ⇒ {
    def filterMetaList: ProdLens[Model, Field] ⇒ List[AbstractMetaAttr] =
      _.metaList.collect { case l: NameMetaAttr ⇒ l }

    val byAdapter = qAdapterRegistry.byName(byClass.getName)
    val genericMaker = generalizedOrigRegistry.get[By](byClass.getName)
    val byDecoded = byAdapter.decode(by).asInstanceOf[By]
    val byGeneric = genericMaker.create("")(byDecoded)
    val prepHolder: LeafInfoHolder[Model, By, Field] = caster(modelCl, byClass, fieldCl)(leafInfo)
    val prepBy: By = prepHolder.check.prepare(prepHolder.byOptions)(byGeneric)
    ProdConditionImpl(filterMetaList(prepHolder.lens), prepBy)(prepHolder.check.check(prepBy), prepHolder.lens.of)
  }
}

case class HashSearchRequestInner[Model](condition: Condition[Model])

@protocol object HashSearchDepRequestProtocolBase {

  trait ProtoDepCondition

  @Id(0x0f37) case class N_HashSearchDepRequest(
    @Id(0x0f3e) modelName: String,
    @Id(0x0f38) condition: LazyOption[ProtoDepCondition]
  ) extends ProtoDepCondition

  case class N_DepConditionIntersect(
    @Id(0x0f46) condLeft: LazyOption[ProtoDepCondition],
    @Id(0x0f47) condRight: LazyOption[ProtoDepCondition],
  ) extends ProtoDepCondition

  case class N_DepConditionUnion(
    @Id(0x0f46) condLeft: LazyOption[ProtoDepCondition],
    @Id(0x0f47) condRight: LazyOption[ProtoDepCondition],
  ) extends ProtoDepCondition

  case class N_DepConditionAny() extends ProtoDepCondition

  case class N_DepConditionLeaf(
    @Id(0x0f3f) modelClass: String,
    @Id(0x0f48) lensName: String,
    @Id(0x0f4b) byClName: String,
    @Id(0x0f3b) value: okio.ByteString,
  ) extends ProtoDepCondition

}