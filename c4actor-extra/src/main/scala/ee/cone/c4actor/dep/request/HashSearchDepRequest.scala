package ee.cone.c4actor.dep.request

import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{DepCondition, HashSearchDepRequest}
import ee.cone.c4actor.utils.{GeneralizedOrigRegistry, GeneralizedOrigRegistryApi}
import ee.cone.c4actor.hashsearch.HashSearchModelsApp
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}

trait ConditionFactoryApp {
trait HashSearchRequestApp extends AssemblesApp with ProtocolsApp with RequestHandlerRegistryApp with GeneralizedOrigRegistryApi {
  override def assembles: List[Assemble] = hashSearchModels.distinct.map(model ⇒ new HSDepRequestAssemble(hsDepRequestHandler, model)) ::: super.assembles

  override def protocols: List[Protocol] = HashSearchDepRequestProtocol :: super.protocols

  def hashSearchModels: List[Class[_ <: Product]] = Nil

  def conditionFactory: ModelConditionFactory[_]
}

trait ConditionFactoryImpl {
  def conditionFactory: ModelConditionFactory[_] = new ModelConditionFactoryImpl[Unit]()
}

trait HashSearchLeafRegistryApp {
  def leafModelRegistry: List[(Class[_ <: Product], LeafRegistry)] = Nil
}
  def qAdapterRegistry: QAdapterRegistry

  def leafRegistry: LeafRegistry

  def hsDepRequestHandler: HashSearchDepRequestHandler = HashSearchDepRequestHandler(leafRegistry, conditionFactory, generalizedOrigRegistry, qAdapterRegistry)
}

case class HashSearchDepRqWrap(srcId: String, request: HashSearchDepRequest, modelCl: String)

@assemble class HSDepRequestAssemble[Model <: Product](hsDepRequestHandler: HashSearchDepRequestHandler, model: Class[Model]) extends Assemble {

  def HSDepRequestWithSrcToItemSrcId(
    key: SrcId,
    request: Values[DepInnerRequest]
  ): Values[(SrcId, Request[Model])] = for {
    rq ← request
    if rq.request.isInstanceOf[HashSearchDepRequest] && rq.request.asInstanceOf[HashSearchDepRequest].modelName == model.getName
  } yield {
    val hsRq = rq.request.asInstanceOf[HashSearchDepRequest]
    WithPK(HashSearch.Request(rq.srcId, hsDepRequestHandler.handle(hsRq).asInstanceOf[Condition[Model]]))
  }

  def HSResponseGrab(
    key: SrcId,
    responses: Values[Response[Model]],
    requests: Values[DepInnerRequest]
  ): Values[(SrcId, DepInnerResponse)] =
    for {
      rq ← requests
      if rq.request.isInstanceOf[HashSearchDepRequest] && rq.request.asInstanceOf[HashSearchDepRequest].modelName == model.getName
      resp ← responses
    } yield {
      WithPK(DepInnerResponse(rq, Option(resp.lines)))
    }
}

trait LeafRegistry {
  def getLeaf(modelCl: String, byCl: String, lensName: String): LeafInfoHolder[_, _ <: Product, _]

  def getModelCl(modelClName: String): Class[_ <: Product]
}

case class LeafInfoHolder[Model, By <: Product, Field](
  lens: ProdLens[Model, Field], byOptions: List[MetaAttr], check: ConditionCheck[By, Field],
  modelCl: Class[Model], byCl: Class[By], fieldCl: Class[Field]
)


case class LeafRegistryImpl(
  leafList: List[LeafInfoHolder[_, _ <: Product, _]],
  models: List[Class[_ <: Product]]
) extends LeafRegistry {

  private lazy val leafMap: Map[(String, String, String), LeafInfoHolder[_, _ <: Product, _]] =
    leafList.map(leaf ⇒ (leaf.modelCl.getName, leaf.byCl.getName, leaf.lens.metaList.collect { case NameMetaAttr(v) ⇒ v }.head) → leaf).toMap

  private lazy val modelMap: Map[String, Class[_ <: Product]] = models.map(cl ⇒ cl.getName → cl).toMap[String, Class[_ <: Product]]

  def getLeaf(modelCl: String, byCl: String, lensName: String): LeafInfoHolder[_, _ <: Product, _] = leafMap((modelCl, byCl, lensName))

  def getModelCl(modelClName: String): Class[_ <: Product] = modelMap(modelClName)
}

case class HashSearchDepRequestHandler(leafs: LeafRegistry, condFactory: ModelConditionFactory[_], generalizedOrigRegistry: GeneralizedOrigRegistry, qAdapterRegistry: QAdapterRegistry) {

  def handle: HashSearchDepRequest => Condition[_] = request ⇒
    parseCondition(
      request.condition.get, condFactory.ofWithCl(
        leafs.getModelCl(request.modelName)
      )
    )


  private def parseCondition[Model](condition: DepCondition, factory: ModelConditionFactory[Model]): Condition[Model] = {
    condition.condType match {
      case "intersect" ⇒ factory.intersect(parseCondition(condition.condLeft.get, factory), parseCondition(condition.condRight.get, factory))
      case "union" ⇒ factory.union(parseCondition(condition.condLeft.get, factory), parseCondition(condition.condRight.get, factory))
      case "any" ⇒ factory.any
      case "leaf" ⇒
        val leafInfo: LeafInfoHolder[_, _ <: Product, _] = leafs.getLeaf(condition.modelClass, condition.by.get.byClName, condition.lensName)
        makeLeaf(leafInfo.modelCl, leafInfo.byCl, leafInfo.fieldCl)(leafInfo, condition.by.get).asInstanceOf[Condition[Model]]
      case _ ⇒ throw new Exception("Not implemented yet: parseBy(by:By)")
    }
  }

  private def caster[Model, By <: Product, Field](m: Class[Model], b: Class[By], f: Class[Field]): LeafInfoHolder[_, _, _] ⇒ LeafInfoHolder[Model, By, Field] =
    _.asInstanceOf[LeafInfoHolder[Model, By, Field]]

  private def makeLeaf[Model, By <: Product, Field](modelCl: Class[Model], byClass: Class[By], fieldCl: Class[Field]):
  (LeafInfoHolder[_, _, _], HashSearchDepRequestProtocol.By) ⇒ ProdConditionImpl[By, Model, Field] = (leafInfo, by) ⇒ {
    def filterMetaList: ProdLens[Model, Field] ⇒ List[MetaAttr] =
      _.metaList.collect { case l: NameMetaAttr ⇒ l }

    val byAdapter = qAdapterRegistry.byName(by.byClName)
    val genericMaker = generalizedOrigRegistry.get[By](byClass.getName)
    val byDecoded = byAdapter.decode(by.value).asInstanceOf[By]
    val byGeneric = genericMaker.create("")(byDecoded)
    val prepHolder: LeafInfoHolder[Model, By, Field] = caster(modelCl, byClass, fieldCl)(leafInfo)
    val prepBy: By = prepHolder.check.prepare(prepHolder.byOptions)(byGeneric)
    ProdConditionImpl(filterMetaList(prepHolder.lens), prepBy)(prepHolder.check.check(prepBy), prepHolder.lens.of)
  }
}

case class HashSearchRequestInner[Model](condition: Condition[Model])

@protocol object HashSearchDepRequestProtocol extends Protocol {

  @Id(0x0f37) case class HashSearchDepRequest(
    @Id(0x0f3e) modelName: String,
    @Id(0x0f38) condition: Option[DepCondition]
  )

  @Id(0x0f44) case class DepCondition(
    @Id(0x0f3f) modelClass: String,
    @Id(0x0f45) condType: String,
    @Id(0x0f46) condLeft: Option[DepCondition],
    @Id(0x0f47) condRight: Option[DepCondition],
    @Id(0x0f48) lensName: String,
    @Id(0x0f49) by: Option[By]
  )

  @Id(0x0f4a) case class By(
    @Id(0x0f4b) byClName: String,
    @Id(0x0f3b) value: okio.ByteString
  )

}