package ee.cone.c4actor.dep.request

import ee.cone.c4actor.HashSearch.{Request, Response}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{DepCondition, HashSearchDepRequest}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}

trait HashSearchRequestApp extends AssemblesApp with ProtocolsApp with LensRegistryAppTrait with RequestHandlerRegistryApp {
  override def assembles: List[Assemble] = hashSearchModels.map(model ⇒ new HSDepRequestAssemble(hsDepRequestHandler, model)) ::: super.assembles

  override def protocols: List[Protocol] = HashSearchDepRequestProtocol :: super.protocols

  def hashSearchModels: List[Class[_ <: Product]]

  def conditionFactory: ModelConditionFactory[_]

  def leafRegistry: LeafRegistry

  def hsDepRequestHandler: HashSearchDepRequestHandler = HashSearchDepRequestHandler(leafRegistry, conditionFactory)
}

@assemble class HSDepRequestAssemble[Model <: Product](hsDepRequestHandler: HashSearchDepRequestHandler, model: Class[Model]) extends Assemble {
  type ToResponse = SrcId
  type HsDepSrcId = SrcId


  def DepRqToResponse(
    key: SrcId,
    @was request: Values[DepRequestWithSrcId]
  ): Values[(HsDepSrcId, DepRequestWithSrcId)] = for {
    rq ← request
  } yield WithPK(rq)

  def HSDepRequestWithSrcToItemSrcId(
    key: SrcId,
    @was request: Values[DepRequestWithSrcId]
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
    @by[HsDepSrcId] requests: Values[DepRequestWithSrcId]
  ): Values[(ToResponse, DepResponse)] =
    (for {
      rq ← requests
      if rq.request.isInstanceOf[HashSearchDepRequest] && rq.request.asInstanceOf[HashSearchDepRequest].modelName == model.getName
      resp ← responses
    } yield {
      val response = DepResponse(rq, Option(resp.lines), rq.parentSrcIds)
      WithPK(response) :: (for (srcId ← response.rqList) yield (srcId, response))
    }).flatten


}

trait ByMaker[By <: Product] {
  def byCl: Class[By]

  def byName: String = byCl.getName

  def make: String ⇒ By
}

trait LeafRegistry {
  def getLeaf(modelCl: String, byCl: String): LeafInfoHolder[_, _ <: Product, _]

  def getByMaker[By <: Product](byClName: String): String ⇒ By

  def getModelCl(modelClName: String): Class[_ <: Product]
}

case class LeafInfoHolder[Model, By <: Product, Field](
  lens: ProdLens[Model, Field], byOptions: List[MetaAttr], check: ConditionCheck[By, Field],
  modelCl: Class[Model], byCl: Class[By], fieldCl: Class[Field]
)


case class LeafRegistryImpl(leafList: List[LeafInfoHolder[_, _ <: Product, _]], byMakers: List[ByMaker[_ <: Product]], models: List[Class[_ <: Product]]) extends LeafRegistry {

  private lazy val byMakerMap: Map[String, String => _ <: Product] = byMakers.map(maker ⇒ maker.byName → maker.make).toMap

  private lazy val leafMap: Map[(String, String), LeafInfoHolder[_, _ <: Product, _]] = leafList.map(leaf ⇒ (leaf.modelCl.getName, leaf.byCl.getName) → leaf).toMap

  private lazy val modelMap: Map[String, Class[_ <: Product]] = models.map(cl ⇒ cl.getName → cl).toMap[String, Class[_ <: Product]]

  def getLeaf(modelCl: String, byCl: String): LeafInfoHolder[_, _ <: Product, _] = leafMap((modelCl, byCl))

  def getByMaker[By <: Product](byClName: String): String ⇒ By = str ⇒ byMakerMap(byClName)(str).asInstanceOf[By]

  def getModelCl(modelClName: String): Class[_ <: Product] = modelMap(modelClName)
}

case class HashSearchDepRequestHandler(leafs: LeafRegistry, condFactory: ModelConditionFactory[_]) {

  def handle: HashSearchDepRequest => Condition[_] = request ⇒
    parseCondition(
      request.condition, condFactory.ofWithCl(
        leafs.getModelCl(request.modelName)
      )
    )


  private def parseCondition[Model](condition: DepCondition, factory: ModelConditionFactory[Model]): Condition[Model] = {
    condition.condType match {
      case "intersect" ⇒ factory.intersect(parseCondition(condition.condLeft.get, factory), parseCondition(condition.condRight.get, factory))
      case "union" ⇒ factory.union(parseCondition(condition.condLeft.get, factory), parseCondition(condition.condRight.get, factory))
      case "any" ⇒ factory.any
      case "leaf" ⇒
        val leafInfo: LeafInfoHolder[_, _ <: Product, _] = leafs.getLeaf(condition.modelClass, condition.by.get.byName)
        makeLeaf(leafInfo.modelCl, leafInfo.byCl, leafInfo.fieldCl)(leafInfo, condition.by.get).asInstanceOf[Condition[Model]]
      case _ ⇒ throw new Exception("Not implemented yet: parseBy(by:By)")
    }
  }

  private def caster[Model, By <: Product, Field](m: Class[Model], b: Class[By], f: Class[Field]): LeafInfoHolder[_, _, _] ⇒ LeafInfoHolder[Model, By, Field] = //TODO Ilya try creating generic version
    _.asInstanceOf[LeafInfoHolder[Model, By, Field]]

  private def makeLeaf[Model, By <: Product, Field](modelCl: Class[Model], byClass: Class[By], fieldCl: Class[Field]): (LeafInfoHolder[_, _, _], HashSearchDepRequestProtocol.By) ⇒ ProdConditionImpl[By, Model, Field] = (leafInfo, by) ⇒ {
    def filterMetaList: ProdLens[Model, Field] ⇒ List[MetaAttr] =
      _.metaList.collect { case l: NameMetaAttr ⇒ l }

    val by2: By = leafs.getByMaker(by.byName)(by.value)
    val prepHolder: LeafInfoHolder[Model, By, Field] = caster(modelCl, byClass, fieldCl)(leafInfo)
    val prepBy: By = prepHolder.check.prepare(prepHolder.byOptions)(by2)
    ProdConditionImpl(filterMetaList(prepHolder.lens), prepBy)(prepHolder.check.check(prepBy), prepHolder.lens.of)
  }
}

case class HashSearchRequestInner[Model](condition: Condition[Model])

@protocol object HashSearchDepRequestProtocol extends Protocol {

  @Id(0x0f37) case class HashSearchDepRequest(
    @Id(0x0f3e) modelName: String,
    @Id(0x0f38) condition: DepCondition
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
    @Id(0x0f4b) byName: String,
    @Id(0x0f3b) value: String
  )

}