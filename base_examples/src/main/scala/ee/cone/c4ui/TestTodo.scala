package ee.cone.c4ui

import java.util.UUID

import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4gate.PublishFromStringsProvider
import ee.cone.c4ui.TestTodoProtocol.{B_TodoTask, B_TodoTaskComments, B_TodoTaskCommentsContains, B_TodoTaskOrder}
import ee.cone.c4proto._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom.{Tags => _, _}
import okio.ByteString
import os.remove



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

abstract class SetField[T](val make: (SrcId,String)=>T) extends Product
@c4multi("TestTodoApp") final case class OnChangeReceiver[T<:Product](
  srcId: SrcId, make: SetField[T]
)(
  txAdd: LTxAdd
) extends Receiver[Context] {
  def receive: Handler = message => local => {
    val value = message.body match { case b: ByteString => b.utf8() }  //.header("x-r-value")
    val events = update(make.make(srcId,value))
    // println(s"$value // $events")
    txAdd.add(events)(local)
  }
}
@c4multi("TestTodoApp") final case class SimpleReceiver(events: Seq[LEvent[Product]])(
  txAdd: LTxAdd
) extends Receiver[Context] {
  def receive: Handler = message => local => txAdd.add(events)(local)
}

@c4tags("TestTodoApp") trait ExampleTags {
  @c4tag("ExampleText") def text(key: String, value: String): VDom[OfDiv]
  @c4tag("ExampleInput") def input(key: String, value: String, change: Receiver[Context]): VDom[OfDiv]
  @c4tag("ExampleButton") def button(key: String, activate: Receiver[Context], caption: String = ""): VDom[OfDiv]
}

@c4("ReactHtmlApp") final class ReactHtmlFromAlienTaskAssembleBase {
  @provide def subAssembles: Seq[Assemble] =
    new FromAlienTaskAssemble("/react-app.html") :: Nil
}

@c4("ReactHtmlApp") final class ReactHtmlProvider extends PublishFromStringsProvider {
  def get: List[(String, String)] = {
    val now = System.currentTimeMillis
    List(
      "/react-app.html" ->
        s"""<!DOCTYPE html><meta charset="UTF-8"><body>
            <script type="text/javascript" src="vendor.js?$now"></script>
            <script type="text/javascript" src="react-app.js?$now"></script>
        </body>"""
    )
  }
}

////////////////////////////////////////////////////////////////////////////////

@c4("TestTodoApp") final class TestTodoUrlProvider {
  @provide def assembles: Seq[Assemble] =
    new FromAlienTaskAssemble("/src/test/todo-app.html") :: Nil
}

@protocol("TestTodoApp") object TestTodoProtocol {
  @Id(0x0001) case class B_TodoTask(@Id(0x0002) srcId: String, @Id(0x0003) createdAt: Long)
  @Id(0x0003) case class B_TodoTaskComments(@Id(0x0002) srcId: String, @Id(0x0003) value: String)
  @Id(0x0002) case class B_TodoTaskOrder(@Id(0x0002) srcId: SrcId, @Id(0x0005) order: List[SrcId])
  @Id(0x0004) case class B_TodoTaskCommentsContains(@Id(0x0002) srcId: SrcId, @Id(0x0003) value: String)
}

case object TodoTaskComments extends SetField(B_TodoTaskComments)
case object TodoTaskCommentsContains extends SetField(B_TodoTaskCommentsContains)

case object DragHandleCellCSSClassName extends CSSClassName{ def name = "dragHandleCell" }

case object HeaderCSSClassName extends CSSClassName{ def name = "tableHeadContainer headerColor" }

@c4("TestTodoApp") final case class TestTodoRootView(locationHash: String = "todo")(
  exampleTags: ExampleTags,
  listTags: ListTags,
  onChangeReceiverFactory: OnChangeReceiverFactory,
  simpleReceiverFactory: SimpleReceiverFactory,
  taskListAddReceiver: TaskListAddReceiver,
  sortReceiverFactory: SortReceiverFactory,
  untilPolicy: UntilPolicy,
  getB_TodoTask: GetByPK[B_TodoTask],
  getB_TodoTaskComments: GetByPK[B_TodoTaskComments],
  getB_TodoTaskCommentsContains: GetByPK[B_TodoTaskCommentsContains],
  todoSortOrderFactory: TodoSortOrderFactory,
  todoSortHandlerFactory: TodoSortHandlerFactory,
) extends ByLocationHashView {
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    val listKey = "taskList"
    val commentsContainsValue = getB_TodoTaskCommentsContains.ofA(local).get(listKey).fold("")(_.value)
    val comments = getB_TodoTaskComments.ofA(local)
    val todoSortOrder = todoSortOrderFactory.create(listKey)
    val allTodoTasks = todoSortOrder.getKeys(local).map(getB_TodoTask.ofA(local))
    val todoTasks = if(commentsContainsValue.isEmpty) allTodoTasks
      else allTodoTasks.filter(
        prod => comments.get(prod.srcId).exists(_.value.contains(commentsContainsValue))
      )
    val expander = listTags.filterButtonExpander("expander",
      minWidth = 1,
      area = RightFilterButtonArea,
      children = List(
        exampleTags.text(
          "button",
          value = "="
        )
      ),
      optButtons = (1 to 2).map { i =>
        listTags.filterButtonPlace(s"bt$i",
          minWidth = 5,
          area = RightFilterButtonArea,
          children = List(
            exampleTags.button(
              "button",
              activate = taskListAddReceiver,
              caption = "+++",
            )
          )
        )
      }.toList
    )

    val list = List(
      listTags.filterArea("todoListFilter",
        centerButtonText = "",
        filters = List(
          listTags.filterItem("comments",
            minWidth = 11, maxWidth = 20,
            canHide = false,
            children = List(
              exampleTags.text("label","Comments contains"),
              exampleTags.input("value",
                value = commentsContainsValue,
                change = onChangeReceiverFactory.create(listKey, TodoTaskCommentsContains),
              )
            )
          )
        ),
        buttons = List(
          listTags.filterButtonPlace("add",
            minWidth = 1,
            area = LeftFilterButtonArea,
            children = List(
              exampleTags.button("button",
                activate = taskListAddReceiver,
                caption = "+",
              )
            )
          ),
          expander
        ),
      ),
      listTags.gridRoot("todoList",
        dragCol = NoReceiver,
        dragRow = sortReceiverFactory.create(todoSortHandlerFactory.create(todoSortOrder)),
        rowKeys = todoTasks.map(_.srcId),
        cols = List(
          GridCol(
            colKey = "drag", width = BoundGridColWidth(1,1),
            hideWill = 0,
          ),
          GridCol(
            colKey = "comments", width = BoundGridColWidth(10,20),
            hideWill = 0,
          ),
          GridCol(
            colKey = "remove", width = BoundGridColWidth(1,1),
            hideWill = 0,
          ),
        ),
        children = List(
          listTags.gridCell(s"comments-head",
            colKey = "comments",
            rowKey = "head",
            className = HeaderCSSClassName,
            children = List(
              exampleTags.text("text","Comments")
            )
          )
        ) ::: todoTasks.flatMap(task=>List(
          listTags.gridCell(s"drag-${task.srcId}",
            colKey = "drag",
            rowKey = task.srcId,
            dragHandle = RowDragHandle,
            children = List(exampleTags.text("text","o")),
            className = DragHandleCellCSSClassName
          ),
          listTags.gridCell(s"comments-${task.srcId}",
            colKey = "comments",
            rowKey = task.srcId,
            children = List(
              exampleTags.input("field",
                comments.get(task.srcId).fold("")(_.value),
                onChangeReceiverFactory.create(task.srcId, TodoTaskComments),
              )
            )
          ),
          listTags.gridCell(s"remove-${task.srcId}",
            colKey = "remove",
            rowKey = task.srcId,
            children = List(exampleTags.button("text",
              caption = "x",
              activate = simpleReceiverFactory.create(delete(task))
            ))
          ),
        ))
      )
    )
    List(listTags.popupManager("pm",
      listTags.highlighter("col-highlighter",RowHighlightByAttr) ::
      listTags.highlighter("row-highlighter",ColHighlightByAttr) ::
      list
    ))
  }
}

case object NoReceiver extends Receiver[Context] {
  def receive: Handler = _ => throw new Exception
}

@c4("TestTodoApp") final case class TaskListAddReceiver(
  txAdd: LTxAdd,
) extends Receiver[Context] {
  def receive: Handler = message => local => {
    val events = update(B_TodoTask(UUID.randomUUID.toString,System.currentTimeMillis))
    txAdd.add(events)(local)
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
) extends SortHandler {
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