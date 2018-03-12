package ee.cone.c4actor.dep.request

import ee.cone.c4actor.HashSearch.{Factory, Request, Response}
import ee.cone.c4actor._
import ee.cone.c4actor.dep.CtxType.ContextId
import ee.cone.c4actor.dep.request.HashSearchDepRequestProtocol.{By, DepCondition, HashSearchDepRequest}
import ee.cone.c4actor.dep.{Dep, RequestDep, RequestHandler, RequestHandlerRegistryApp}
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.reflect.runtime.{universe => un}

trait HashSearchRequestApp extends AssemblesApp with ProtocolsApp with LensRegistryAppTrait with RequestHandlerRegistryApp {
  override def assembles: List[Assemble] = super.assembles // TODO fill in assembler here

  override def protocols: List[Protocol] = HashSearchDepRequestProtocol :: super.protocols

  def hashSearchModels: List[Class[_ <: Product]]

  def conditionFactory: ModelConditionFactory[_]

  //override def handlers: List[RequestHandler[_]] = hashSearchModels.map(clas ⇒ HashSearchDepRequestHandler(/*TODO finish this tomorrow*/)) ::: super.handlers
}

/*trait LensRegistryAppTrait {
  def lensList: List[ProdLens[_, _]] = Nil

  def lensRegistry: LensRegistry
}

trait LensRegistryApp extends LensRegistryAppTrait {
  def lensRegistry = LensRegistryImpl(lensList)
}*/

trait LeafRegistry {
  def get(leafName: String): LeafInfoHolder[_, _ <: Product, _]
}

trait ByMaker {
  def byName: String

  def make[By]: String ⇒ By
}

case class ByFactory(byMakers: List[ByMaker]) {
  lazy val byMakerMap = byMakers.map(maker ⇒ maker.byName → maker.make).toMap

  def make[By](byName: String): String ⇒ By = byMakerMap(byName)
}

case class LeafInfoHolder[Model, By <: Product, Field](lens: ProdLens[Model, Field], byOptions: List[MetaAttr], check: ConditionCheck[By, Field], modelCl: Class[Model], byCl: Class[By], fieldCl: Class[Field])

// lens, by, byOpt, condCheck
case class LeafRegistryImpl(leafList: List[LeafInfoHolder[_, _ <: Product, _]]) extends LeafRegistry {

  lazy val leafMap: Map[String, LeafInfoHolder[_, _ <: Product, _]] = leafList.map(leaf ⇒ (s"${leaf.modelCl.getName},${leaf.byCl.getName}", leaf)).toMap

  def get(leafName: String): LeafInfoHolder[_, _ <: Product, _] = {
    leafMap(leafName)
  }
}

case class ModelClassRegistry(list: List[Class[_ <: Product]]) {
  lazy val classMap = list.map(cl ⇒ cl.getName → cl).toMap[String, Class[_ <: Product]]
}

case class HashSearchDepRequestHandler(modelCl: ModelClassRegistry, leafs: LeafRegistry, byFactory: ByFactory, condFactory: ModelConditionFactory[_]) extends RequestHandler[HashSearchDepRequest] {
  def canHandle: Class[HashSearchDepRequest] = classOf[HashSearchDepRequest]

  def getCl: String ⇒ Class[_ <: Product] = modelCl.classMap

  def test = classOf[String]

  def handle: HashSearchDepRequest => (Dep[_], ContextId) = request ⇒ new RequestDep[Response[_]](HashSearchRequestInner(parseCondition(request.condition, condFactory.ofWithCl(getCl(request.modelName))))) → ""

  private def parseCondition[Model](condition: DepCondition, factory: ModelConditionFactory[Model]): Condition[Model] = {
    condition.condType match {
      case "intersect" ⇒ factory.intersect(parseCondition(condition.condLeft.get, factory), parseCondition(condition.condRight.get, factory))
      case "union" ⇒ factory.union(parseCondition(condition.condLeft.get, factory), parseCondition(condition.condRight.get, factory))
      case "any" ⇒ factory.any
      //case _ ⇒ throw new Exception("leaf in not done")
      case "leaf" ⇒
        val leafInfo = leafs.get(s"${condition.modelClass},${condition.by.get.byName}")
        makeLeaf(leafInfo.modelCl, leafInfo.byCl, leafInfo.fieldCl)(leafInfo, condition.by.get).asInstanceOf[Condition[Model]]
      case _ ⇒ throw new Exception("Not implemented yet: parseBy(by:By)")
    }
  }


  def caster[Model, By <: Product, Field](m: Class[Model], b: Class[By], f: Class[Field]): LeafInfoHolder[_, _, _] ⇒ LeafInfoHolder[Model, By, Field] = _.asInstanceOf[LeafInfoHolder[Model, By, Field]]

  def makeLeaf[Model, By <: Product, Field](modelCl: Class[Model], byClass: Class[By], fieldCl: Class[Field]): (LeafInfoHolder[_, _, _], HashSearchDepRequestProtocol.By) ⇒ ProdConditionImpl[By, Model, Field] = (leafInfo, by) ⇒ {
    def filterMetaList: ProdLens[Model, Field] ⇒ List[MetaAttr] =
      _.metaList.collect { case l: NameMetaAttr ⇒ l }

    val by2: By = byFactory.make(by.byName)(by.value)
    val prepHolder: LeafInfoHolder[Model, By, Field] = caster(modelCl, byClass, fieldCl)(leafInfo)
    val prepBy: By = prepHolder.check.prepare(prepHolder.byOptions)(by2)
    ProdConditionImpl(filterMetaList(prepHolder.lens), prepBy)(prepHolder.check.check(prepBy), prepHolder.lens.of)
  }


  def filterMetaList[Model, Field]: ProdLens[Model, Field] ⇒ List[MetaAttr] =
    _.metaList.collect { case l: NameMetaAttr ⇒ l }

  private def convert[Model, Field](modelCl: Class[Model], fieldCl: Class[Field]): ProdLens[_, _] ⇒ ProdLens[Model, Field] = _.asInstanceOf[ProdLens[Model, Field]]

  /*private def parseBy(by: By): StrEq = by.byName match {
    case "StrEq" ⇒ StrEq(by.value)
    case _ ⇒ throw new Exception("Not implemented yet: parseCondition(condition: DepCondition)")
  }*/
}

/*case class ConditionTransformerRegistry(conditions: List[ConditionTransformer]) {
  lazy val map
  (className → ConditionTransformer
  )

  def get: className → ConditionTransformer
}*/

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

/*
case class HashSearchDepRequestHandler(condFactory: ModelConditionFactory[_], rqFactory : Factory) {
  def handle[Model <: Product]: HashSearchDepRequest[Model] => (Request[Model]) = rq ⇒ {
    val factory = condFactory.of[Model]
    val condition = getCondition(factory, rq.condition)
    rqFactory.request(condition)
  }

  private def getCondition[Model](fact: ModelConditionFactory[Model], depCondition: DepCondition[Model]): Condition[Model] =
    depCondition match {
      case _: DepAny[Model] ⇒ fact.any
      case a: DepLeaf[Model, _, _] ⇒ fact.leaf(a.lens, a.by, a.byOptions)
      case a: DepUnion[Model] ⇒ fact.union(getCondition(fact, a.left), getCondition(fact,a.right))
      case a: DepIntersect[Model] ⇒ fact.intersect(getCondition(fact, a.left), getCondition(fact,a.right))
    }

}

case class HashSearchDepRequest[Model](condition: DepCondition[Model])

trait DepCondition[Model <: Product] extends Product {
  def toUniqueString: String

  def modelName: String
}

case class DepIntersect[Model: un.TypeTag](left: DepCondition[Model], right: DepCondition[Model]) extends DepCondition[Model] {
  def toUniqueString: String = s"$productPrefix(${left.toUniqueString},${right.toUniqueString})"

  def modelName: String = un.typeTag[Model].toString()
}

case class DepUnion[Model: un.TypeTag](left: DepCondition[Model], right: DepCondition[Model]) extends DepCondition[Model] {
  def toUniqueString: String = s"$productPrefix(${left.toUniqueString},${right.toUniqueString})"

  def modelName: String = un.typeTag[Model].toString()
}

case class DepAny[Model: un.TypeTag]() extends DepCondition[Model] {
  def toUniqueString: String = s"DepAny($modelName)"

  def modelName: String = un.typeTag[Model].toString()
}

case class DepLeaf[Model: un.TypeTag, Field, By <: Product](lens: ProdLens[Model, Field], by: By, byOptions: List[MetaAttr]) extends DepCondition[Model] {
  def toUniqueString: String = s"$productPrefix([${lens.metaList.mkString(",")}],${by.toString},[${lens.metaList.mkString(",")}])"

  def modelName: String = un.typeTag[Model].toString()
}
*/
case class A[Model](l: String, cl: Class[Model]) {
  def str = cl.getName
}

trait B[Model] {
  def draw = "sdlfdsklfdsjsd"
}

case class A2[Model: un.TypeTag](l: String) extends B[Model] {
  def str = un.typeTag[Model].toString()
}

case class Model(value: String)

case class StrEq(value: String) //todo proto
case object StrEqCheck extends ConditionCheck[StrEq, String] {
  def prepare: List[MetaAttr] ⇒ StrEq ⇒ StrEq = _ ⇒ identity[StrEq]

  def check: StrEq ⇒ String ⇒ Boolean = by ⇒ value ⇒ value == by.value
}

case object StrEqMaker extends ByMaker {
  override def byName: String = classOf[StrEq].getName

  override def make[By]: String => By = input ⇒ StrEq(input).asInstanceOf[By]
}

object Test {
  def main(args: Array[String]): Unit = {
    val modelClassRegistry = ModelClassRegistry(List(classOf[Model]))
    val lens = ProdLens.ofSet[Model, String](_.value, l => _.copy(value = l), "ModelLens")
    val leafRegistry = LeafRegistryImpl(List(LeafInfoHolder(lens, Nil, StrEqCheck, classOf[Model], classOf[StrEq], classOf[String])))
    val byFactory = ByFactory(List(StrEqMaker))
    val handler = HashSearchDepRequestHandler(modelClassRegistry, leafRegistry, byFactory, new ModelConditionFactoryImpl)
    val leaf = DepCondition(classOf[Model].getName, "leaf", None, None, "ModelLens", Option(By(classOf[StrEq].getName, "123")))
    val any = DepCondition(classOf[Model].getName, "any", None, None, "", None)
    val conjunction = DepCondition(classOf[Model].getName, "union", Option(leaf), Option(any), "", None)
    val request = HashSearchDepRequest(classOf[Model].getName, conjunction)
    println(handler.handle(request)._1.asInstanceOf[RequestDep[_]].request.toString)
  }
}

/*
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
}*/

/*
/*
@Id(0x0f39) case class Intersect(
     condLeft: Condition,
     condRight: Condition
  ) extends Condition

  @Id(0x0f3c) case class Union(
    @Id(0x0f3d) condLeft: Condition,
    @Id() condRight: Condition
  ) extends Condition

  @Id(0x0f3f) case class Any() extends Condition

  @Id(0x0f40) case class Leaf(
    @Id(0x0f41) lensName: String,
    @Id(0x0f42) by: By
  ) extends Condition

  trait By

  @Id(0x0f43) case class StrEq() extends By
 */*/