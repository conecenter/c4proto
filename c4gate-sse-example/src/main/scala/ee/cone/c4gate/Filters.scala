package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.CommonFilterProtocol.{Contains, DateBefore}
import ee.cone.c4proto._
import ee.cone.c4ui._
import ee.cone.c4vdom.{ChildPair, OfDiv}

//todo access Meta from here, ex. precision
//todo ask if predicate active ?Option[Field=>Boolean]

//// mix

trait CommonFilterApp extends `The CommonFilterConditionChecksImpl`
  with CommonFilterInjectApp with `The DateBeforeAccessView` with `The ContainsAccessView`

trait CommonFilterInjectApp extends `The DateBeforeDefault` with `The ContainsDefault`

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

trait CommonFilterConditionChecks {
  implicit def dateBefore: ConditionCheck[DateBefore,Long]
  implicit def contains: ConditionCheck[Contains,String]
}

//// impl

@c4component @listed case class DateBeforeDefault() extends DefaultModelFactory(classOf[DateBefore],DateBefore(_,None))
@c4component @listed case class ContainsDefault() extends DefaultModelFactory(classOf[Contains],Contains(_,""))

case object DateBeforeCheck extends ConditionCheck[DateBefore,Long] {
  def prepare: List[MetaAttr] ⇒ DateBefore ⇒ DateBefore = _ ⇒ identity[DateBefore]
  def check: DateBefore ⇒ Long ⇒ Boolean = by ⇒ value ⇒ by.value forall (_>value)
}

case object ContainsCheck extends ConditionCheck[Contains,String] {
  def prepare: List[MetaAttr] ⇒ Contains ⇒ Contains = _ ⇒ identity[Contains]
  def check: Contains ⇒ String ⇒ Boolean = by ⇒ value ⇒ value contains by.value
}

@c4component case class CommonFilterConditionChecksImpl() extends CommonFilterConditionChecks {
  lazy val dateBefore: ConditionCheck[DateBefore,Long] = DateBeforeCheck
  lazy val contains: ConditionCheck[Contains,String] = ContainsCheck
}

@c4component @listed case class DateBeforeAccessView(
  testTags: TestTags,
  dateBeforeValue: ProdLens[DateBefore,Option[Long]] = ProdLens.of(_.value)
) extends AccessView(classOf[DateBefore]) {
  def view(access: Access[DateBefore]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.dateInput(access to dateBeforeValue))
}

@c4component @listed case class ContainsAccessView(
  testTags: TestTags,
  containsValue: ProdLens[Contains,String] = ProdLens.of(_.value)
) extends AccessView(classOf[Contains]) {
  def view(access: Access[Contains]): Context⇒List[ChildPair[OfDiv]] =
    local ⇒ List(testTags.input(access to containsValue))
}
