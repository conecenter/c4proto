package ee.cone.c4ui

import java.util.UUID

import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4gate._
import ee.cone.c4ui.CommonFilterProtocol._
import ee.cone.c4ui.TestTodoProtocol.{B_TodoTask, B_TodoTaskOrder}
import ee.cone.c4proto._
import ee.cone.c4vdom.{ChildPair, OfDiv, SortHandler, SortTags, TagStyles, Tags}
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}

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
  @Id(0x0002) case class B_TodoTaskOrder(
    @Id(0x0002) srcId: SrcId,
    @Id(0x0005) order: List[SrcId]
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
  sortTags: SortTags,
  styles: TagStyles,
  contextAccess: RModelAccessFactory,
  filterPredicates: FilterPredicateBuilder,
  commonFilterConditionChecks: CommonFilterConditionChecks,
  accessViewRegistry: AccessViewRegistry,
  untilPolicy: UntilPolicy,
  getB_TodoTask: GetByPK[B_TodoTask],
  txAdd: LTxAdd,
  todoSortOrderFactory: TodoSortOrderFactory,
  todoSortHandlerFactory: TodoSortHandlerFactory,
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
        txAdd.add(update(B_TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )(List(text("text","+")))
    )

    def cell(key: VDomKey, content: ChildPair[OfDiv]): ChildPair[OfDiv] =
      div(key,List(styles.width(100),styles.displayInlineBlock))(List(content))

    val todoSortOrder = todoSortOrderFactory.create("todoSortOrderId")
    val todoTasks = todoSortOrder.getKeys(local).map(getB_TodoTask.ofA(local))
      .filter(filterPredicate.condition.check)
    
    val taskLines = for {
      prod <- todoTasks
      task <- contextAccess.to(getB_TodoTask,prod)
    } yield tags.row(prod.srcId,List(
      tags.cell("sortHandle",sortTags.handle("sortHandle",text("caption","o"))),
      tags.cell("input",tags.input(task to comments)),
      tags.cell("remove",
        divButton("remove")(txAdd.add(delete(prod)))(List(text("caption","-")))
      )
    ))

    val todoSortHandler = todoSortHandlerFactory.create(todoSortOrder)
    val table = tags.table("table",List(
      tags.tHead(List(
        tags.row("head",List(
          tags.cell("sortHandle",text("sortHandle","drag")),
          tags.cell("input",text("input","comments")),
          tags.cell("remove",text("remove","remove")),
        ))
      )),
      sortTags.tBodyRoot(todoSortHandler,taskLines)
    ))

    tags.containerLeftRight("clr",filterList,btnList) :: table :: Nil
  }
}

@c4multi("TestTodoApp") final case class TodoSortOrder(orderSrcId: SrcId)(
  getB_TodoTask: GetByPK[B_TodoTask],
  getB_TodoTaskOrder: GetByPK[B_TodoTaskOrder],
) {
  def getKeys: Context => List[SrcId] = local => {
    val wasOrder = getB_TodoTaskOrder.ofA(local).get(orderSrcId).fold(List.empty[SrcId])(_.order)
    val todoTaskMap = getB_TodoTask.ofA(local)
    todoTaskMap.keys.filterNot(wasOrder.toSet).map(todoTaskMap).toList.sortBy(-_.createdAt).map(_.srcId) ::: wasOrder.filter(todoTaskMap.contains)
  }
  def events(keys: List[SrcId]): Seq[LEvent[Product]] =
    LEvent.update(B_TodoTaskOrder(orderSrcId,keys))
}

@c4multi("TestTodoApp") final case class TodoSortHandler(todoSortOrder: TodoSortOrder)(
  txAdd: LTxAdd
) extends SortHandler[Context] {
  def handle(
    objKey: VDomKey,
    orderKeys: (VDomKey,VDomKey)
  ): Context => Context = local => {
    val (orderKey0,orderKey1) = orderKeys
    val order = todoSortOrder.getKeys(local).flatMap{
      case k if k == objKey => Nil
      case k if k == orderKey0 || k == orderKey1 => List(orderKey0,orderKey1)
      case k => List(k)
    }
    txAdd.add(todoSortOrder.events(order))(local)
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