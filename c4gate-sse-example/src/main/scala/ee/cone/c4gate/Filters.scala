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
  implicit def dateBeforePredicateFactory: FilterPredicateFactory[DateBefore,Long]
  implicit def containsPredicateFactory: FilterPredicateFactory[Contains,String]
}

//// impl

object DateBeforeDefault extends DefaultModelFactory(classOf[DateBefore],DateBefore(_,None))
object ContainsDefault extends DefaultModelFactory(classOf[Contains],Contains(_,""))

object DateBeforePredicateFactory extends FilterPredicateFactory[DateBefore,Long] {
  def create(by: DateBefore): Long⇒Boolean = value ⇒ by.value forall (_>value)
}

object ContainsPredicateFactory extends FilterPredicateFactory[Contains,String] {
  def create(by: Contains): String⇒Boolean = value ⇒ value contains by.value
}

object CommonFilterPredicateFactoriesImpl extends CommonFilterPredicateFactories {
  lazy val dateBeforePredicateFactory: FilterPredicateFactory[DateBefore,Long] = DateBeforePredicateFactory
  lazy val containsPredicateFactory: FilterPredicateFactory[Contains,String] = ContainsPredicateFactory
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


