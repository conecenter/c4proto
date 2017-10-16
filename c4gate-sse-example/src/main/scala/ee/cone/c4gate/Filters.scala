package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.fieldAccess
import ee.cone.c4gate.CommonFilterProtocol.{Contains, DateBefore}
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui.{AccessView, AccessViewsApp}
import ee.cone.c4vdom.{ChildPair, OfDiv}

//todo access Meta from here, ex. precision
//todo ask if predicate active ?Option[Field=>Boolean]

//// mix

trait CommonFilterApp extends CommonFilterPredicateFactoriesApp
  with CommonFilterInjectApp with DateBeforeAccessViewApp with ContainsAccessViewApp

trait CommonFilterPredicateFactoriesApp {
  lazy val commonFilterPredicateFactories: CommonFilterPredicateFactories =
    CommonFilterPredicateFactoriesImpl
}

trait CommonFilterInjectApp extends DefaultModelFactoriesApp {
  override def defaultModelFactories: List[DefaultModelFactory[_]] =
    DateBeforeDefault :: ContainsDefault :: super.defaultModelFactories
}

trait DateBeforeAccessViewApp extends AccessViewsApp {
  def testTags: TestTags[Context]
  private lazy val dateBeforeAccessView = new DateBeforeAccessView(testTags)
  override def accessViews: List[AccessView[_]] = dateBeforeAccessView :: super.accessViews
}
trait ContainsAccessViewApp extends AccessViewsApp {
  def testTags: TestTags[Context]
  private lazy val containsAccessView = new ContainsAccessView(testTags)
  override def accessViews: List[AccessView[_]] = containsAccessView :: super.accessViews
}


//// api

@protocol object CommonFilterProtocol extends Protocol {
  @Id(0x0006) case class DateBefore(
    @Id(0x0001) srcId: String,
    @Id(0x0002) value: Option[Long]
  )
  @Id(0x0007) case class Contains(
    @Id(0x0001) srcId: String,
    @Id(0x0002) value: String
  )
}

trait CommonFilterPredicateFactories {
  implicit def dateBeforePredicateFactory: LeafConditionFactory[DateBefore,Long]
  implicit def containsPredicateFactory: LeafConditionFactory[Contains,String]
}

//// impl

object DateBeforeDefault extends DefaultModelFactory(classOf[DateBefore],DateBefore(_,None))
object ContainsDefault extends DefaultModelFactory(classOf[Contains],Contains(_,""))

object DateBeforeCheck extends LeafConditionFactory[DateBefore,Long] {
  def create[Model] = Expression.apply
  case class Expression[Model](lens: ProdLens[Model,Long], by: DateBefore) extends Condition[Model] {
    def check(model: Model): Boolean = by.value forall (_>lens.of(model))
  }
}

object ContainsCheck extends LeafConditionFactory[Contains,String] {
  def create[Model] = Expression.apply
  case class Expression[Model](lens: ProdLens[Model,String], by: Contains) extends Condition[Model] {
    def check(model: Model): Boolean = lens.of(model) contains by.value
  }
}

object CommonFilterPredicateFactoriesImpl extends CommonFilterPredicateFactories {
  lazy val dateBeforePredicateFactory: LeafConditionFactory[DateBefore,Long] = DateBeforeCheck
  lazy val containsPredicateFactory: LeafConditionFactory[Contains,String] = ContainsCheck
}

class DateBeforeAccessView(testTags: TestTags[Context]) extends AccessView(classOf[DateBefore]) {
  def view(access: Access[DateBefore]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.dateInput(access to CommonFilterAccess.dateBeforeValue))
}

class ContainsAccessView(testTags: TestTags[Context]) extends AccessView(classOf[Contains]) {
  def view(access: Access[Contains]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.input(access to CommonFilterAccess.containsValue))
}

@fieldAccess object CommonFilterAccess {
  lazy val dateBeforeValue: ProdLens[DateBefore,Option[Long]] = ProdLens.of(_.value)
  lazy val containsValue: ProdLens[Contains,String] = ProdLens.of(_.value)
}


