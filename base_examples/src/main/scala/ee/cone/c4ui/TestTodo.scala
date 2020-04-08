package ee.cone.c4ui

import java.util.UUID

import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, provide}
import ee.cone.c4gate._
import ee.cone.c4ui.CommonFilterProtocol._
import ee.cone.c4ui.TestTodoProtocol.B_TodoTask
import ee.cone.c4proto._
import ee.cone.c4vdom.{TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes

// @c4mod class FooCargoType extends CargoType

// @c4mod class CargoTypeRegistry(items: List[CargoType]) extends Registry
// @c4mod class CargoTypeToPartViewProvider(reg: CargoTypeRegistry) {
//   def providePartViews(): Seq[PartView] =

// ! multi layer module tree
// ! reg at Impl; c4mod traits by all extends
// @c4mod class PartViewRegistry(views: List[PartView]) extends Registry
// @c4mod class BarPartView() extends PartView


// ee.cone.aaa.bbb.
// ConeAaaBbbApp
// @assembleModApp

//


//trait TestTodoAppBase extends ServerCompApp
//@protocol("TestTodoApp")

// @assemble("ReactHtmlApp")
// @assembleAtReactHtmlApp
// @c4("TestTodoApp")
// @mixAtTestTodoAutoApp
// @mixMod == @mixAtCargoStuff
// @partOf*

// @c4("BazApp") class FooHolderImpl(bar: Bar) extends FooHolder(new FooImpl)
// vs
// @c4("BazApp") class Baz(...) {
//   def mixFoo(bar: Bar): Foo = new Foo
//   def mixHoo(bar: Goo): Hoo = new Hoo



@c4("ReactHtmlApp") final class ReactHtmlFromAlienTaskAssembleBase {
  @provide def subAssembles: Seq[Assemble] =
    new FromAlienTaskAssemble("/react-app.html") :: Nil
}

@protocol("TestTodoApp") object TestTodoProtocol   {
  @Id(0x0001) case class B_TodoTask(
    @Id(0x0002) srcId: String,
    @Id(0x0003) createdAt: Long,
    @Id(0x0004) comments: String
  )
}

import TestTodoAccess._
@fieldAccess object TestTodoAccessBase {
  lazy val comments: ProdLensStrict[B_TodoTask,String] =
    ProdLens.of(_.comments, UserLabel en "(comments)")
  lazy val createdAt: ProdLensStrict[B_TodoTask,Long] =
    ProdLens.of(_.createdAt, UserLabel en "(created at)")
  lazy val createdAtFlt =
    SessionAttr(Id(0x0006), classOf[B_DateBefore], UserLabel en "(created before)")
  lazy val commentsFlt =
    SessionAttr(Id(0x0007), classOf[B_Contains], IsDeep, UserLabel en "(comments contain)")
}

/*
trait TestTodoRootViewApp extends ByLocationHashViewsApp {
  def testTags: TestTags[Context]
  def tags: Tags
  def tagStyles: TagStyles
  def modelAccessFactory: ModelAccessFactory
  def filterPredicateBuilder: FilterPredicateBuilder
  def commonFilterConditionChecks: CommonFilterConditionChecks
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def accessViewRegistry: AccessViewRegistry
  def untilPolicy: UntilPolicy

  private lazy val testTodoRootView = TestTodoRootView()(
    testTags,
    tags,
    tagStyles,
    modelAccessFactory,
    filterPredicateBuilder,
    commonFilterConditionChecks,
    accessViewRegistry,
    untilPolicy
  )

  override def byLocationHashViews: List[ByLocationHashView] =
    testTodoRootView :: super.byLocationHashViews
}*/

@c4("TestTodoApp") final case class TestTodoRootView(locationHash: String = "todo")(
  tags: TestTags[Context],
  mTags: Tags,
  styles: TagStyles,
  contextAccess: RModelAccessFactory,
  filterPredicates: FilterPredicateBuilder,
  commonFilterConditionChecks: CommonFilterConditionChecks,
  accessViewRegistry: AccessViewRegistry,
  untilPolicy: UntilPolicy,
  getB_TodoTask: GetByPK[B_TodoTask],
) extends ByLocationHashView {
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    import mTags._
    import commonFilterConditionChecks._
    val filterPredicate = filterPredicates.create[B_TodoTask](local)
      .add(commentsFlt, comments)
      .add(createdAtFlt, createdAt)

    val filterList = for {
      access <- filterPredicate.accesses
      tag <- accessViewRegistry.view(access)(local)
    } yield tag
    // filterPredicate.accesses.flatMap { case a if a.initialValue => List(a to sub1, a to sub2) case a => List(a) }

    val btnList = List(
      divButton("add")(
        TxAdd(update(B_TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )(List(text("text","+")))
    )

    val todoTasks = getB_TodoTask.ofA(local).values
      .filter(filterPredicate.condition.check).toList.sortBy(-_.createdAt)
    val taskLines = for {
      prod <- todoTasks
      task <- contextAccess.to(getB_TodoTask,prod)
    } yield div(prod.srcId,Nil)(List(
      tags.input(task to comments),
      div("remove",List(styles.width(100),styles.displayInlineBlock))(List(
        divButton("remove")(TxAdd(delete(prod)))(List(text("caption","-")))
      ))
    ))

    tags.containerLeftRight("clr",filterList,btnList) :: taskLines
  }
}

/*
branches:
    S_BranchResult --> BranchRel-s
    S_BranchResult [prev] + BranchRel-s --> BranchTask [decode]
    ...
    BranchHandler + BranchRel-s + MessageFromAlien-s -> TxTransform
ui:
    FromAlienState --> BranchRel [encode]
    BranchTask --> FromAlienTask [match host etc]
    BranchTask + View --> BranchHandler
    BranchTask + CanvasHandler --> BranchHandler
custom:
    FromAlienTask --> View [match hash]
    BranchTask --> CanvasHandler

S_BranchResult --> BranchRel-s --> BranchTask --> [custom] --> BranchHandler --> TxTransform
*/