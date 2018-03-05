package ee.cone.c4actor.dep.request

import ee.cone.c4actor.HashSearch.Response
import ee.cone.c4actor._
import ee.cone.c4actor.dep.CtxType.ContextId
import ee.cone.c4actor.dep.{Dep, RequestDep, RequestHandler, RequestHandlerRegistryApp}
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{By, DepCondition, HashSearchDepRequest, StrEq}
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.{Id, Protocol, protocol}

trait HashSearchRequestApp extends AssemblesApp with ProtocolsApp with LensRegistryAppTrait with RequestHandlerRegistryApp {
  override def assembles: List[Assemble] = super.assembles // TODO fill in assembler here

  override def protocols: List[Protocol] = HashSearchDepRequestProtocol :: super.protocols

  def hashSearchModels: List[Class[_ <: Product]]

  def conditionFactory: ModelConditionFactory[_]

  override def handlers: List[RequestHandler[_]] = hashSearchModels.map(clas ⇒ HashSearchDepRequestHandler(/*TODO finish this tomorrow*/)) ::: super.handlers
}

case class HashSearchDepRequestHandler[Model](lensRegistry: LensRegistry, modelClass: Class[Model], condFactory: ModelConditionFactory[Model]) extends RequestHandler[HashSearchDepRequest] {
  def canHandle: Class[HashSearchDepRequest] = classOf[HashSearchDepRequest]

  def handle: HashSearchDepRequest => (Dep[_], ContextId) = request ⇒ new RequestDep[Response[Model]](HashSearchRequestInner(parseCondition(request.condition))) → ""

  private def parseCondition(condition: DepCondition): Condition[Model] = condition.condType match {
    case "intersect" ⇒ condFactory.intersect(parseCondition(condition.condLeft.get), parseCondition(condition.condRight.get))
    case "union" ⇒ condFactory.union(parseCondition(condition.condLeft.get), parseCondition(condition.condRight.get))
    case "any" ⇒ condFactory.any
    case "leaf" ⇒ condFactory.leaf[StrEq, String](lensRegistry.get(condition.lensName).asInstanceOf[ProdLens[Model, String]], parseBy(condition.by.get), Nil) //TODO Ilya fix this hard code
    case _ ⇒ throw new Exception("Not implemented yet: parseBy(by:By)")
  }

  private def parseBy(by: By): StrEq = by.byName match {
    case "StrEq" ⇒ StrEq(by.value)
    case _ ⇒ throw new Exception("Not implemented yet: parseCondition(condition: DepCondition)")
  }
}

case class HashSearchRequestInner[Model](condition: Condition[Model])

@protocol object HashSearchDepRequestProtocol extends Protocol {

  @Id(0x0f37) case class HashSearchDepRequest(
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

  @Id(0x0f39) case class StrEq(
    @Id(0x0f3a) value: String
  )

}

case object StrEqCheck extends ConditionCheck[StrEq, String] {
  def prepare: List[MetaAttr] ⇒ StrEq ⇒ StrEq = _ ⇒ identity[StrEq]

  def check: StrEq ⇒ String ⇒ Boolean = by ⇒ value ⇒ value == by.value
}

case object StrEqRanger extends Ranger[StrEq, String] {
  def ranges: StrEq ⇒ (String ⇒ List[StrEq], PartialFunction[Product, List[StrEq]]) = {
    case StrEq("") ⇒ (
      value ⇒ List(StrEq(value)), {
      case p@StrEq(v) ⇒ List(p)
    }
    )
  }
}

/*
@Id(0x0f39) case class Intersect(
     condLeft: Condition,
     condRight: Condition
  ) extends Condition

  @Id(0x0f3c) case class Union(
    @Id(0x0f3d) condLeft: Condition,
    @Id(0x0f3e) condRight: Condition
  ) extends Condition

  @Id(0x0f3f) case class Any() extends Condition

  @Id(0x0f40) case class Leaf(
    @Id(0x0f41) lensName: String,
    @Id(0x0f42) by: By
  ) extends Condition

  trait By

  @Id(0x0f43) case class StrEq() extends By
 */