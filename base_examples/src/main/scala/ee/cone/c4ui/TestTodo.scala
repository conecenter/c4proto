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
import com.typesafe.scalalogging.LazyLogging


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

@c4tags("TestTodoApp") trait ExampleTags[C] {
  @c4el("ExampleText") def text(key: String, value: String): ToChildPair
  @c4el("ExampleInput") def input(key: String, value: String, change: Receiver[C]): ToChildPair
  @c4el("ExampleButton") def button(key: String, activate: Receiver[C], caption: String = ""): ToChildPair
}

@c4("ReactHtmlApp") final class ReactHtmlProvider extends PublishFromStringsProvider {
  def get: List[(String, String)] = {
    val now = System.currentTimeMillis
    List(
      "/react-app.html" ->
        s"""<!DOCTYPE html><meta charset="UTF-8"><body>
            <script type="text/javascript" src="vendor.js?$now"></script>
            <script type="text/javascript" src="react-app.js?$now"></script>
        </body>""",
      "/todo-app.html" ->
        s"""<!DOCTYPE html><meta charset="UTF-8">
         <style> @import '/src/c4p/test/todo-app.css'; </style>
         <body>
            <script  type="module" src="/src/c4p/test/todo-app.js?$now"></script>
         </body>""",

      "/ws-app.html" -> (
        """<!DOCTYPE html><meta charset="UTF-8">""" +
        s"""<body><script  type="module" src="/src/c4p/test/ws-app.js?$now"></script></body>"""
      ),

    )
  }
}

////////////////////////////////////////////////////////////////////////////////

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
  exampleTagsProvider: ExampleTagsProvider,
  listTagsProvider: ListTagsProvider,
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
)(
  val listTags: ListTags[Context] = listTagsProvider.get[Context],
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context]
) extends ByLocationHashView with LazyLogging {
  import listTags._
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
    val expander = filterButtonExpander("expander",
      area = rightFilterButtonArea,
      children = List(
        exampleTags.text(
          "button",
          value = "="
        ).toChildPair[OfDiv]
      ),
      optButtons = (1 to 2).map { i =>
        filterButtonPlace(s"bt$i",
          area = rightFilterButtonArea,
          children = List(
            exampleTags.button(
              "button",
              activate = taskListAddReceiver,
              caption = "+++",
            ).toChildPair[OfDiv]
          )
        )
      }.toList
    )

    val list = List(
      filterArea("todoListFilter",
        filters = List(
          filterItem("comments",
            minWidth = 11, maxWidth = 20,
            canHide = false,
            children = List(
              exampleTags.text("label","Comments contains").toChildPair[OfDiv],
              exampleTags.input("value",
                value = commentsContainsValue,
                change = onChangeReceiverFactory.create(listKey, TodoTaskCommentsContains),
              ).toChildPair[OfDiv]
            )
          )
        ),
        buttons = List(
          filterButtonPlace("add",
            area = leftFilterButtonArea,
            children = List(
              exampleTags.button("button",
                activate = taskListAddReceiver,
                caption = "+",
              ).toChildPair[OfDiv]
            )
          ),
          filterButtonPlace("of",
            area = rightFilterButtonArea,
            children = List(
              exampleTags.text("of","of").toChildPair[OfDiv],
            )
          ),
          expander
        ),
      ).toChildPair[OfDiv],
      gridRoot("todoList",
        dragCol = TaskNoReceiver,
        dragRow = sortReceiverFactory.create(todoSortHandlerFactory.create(todoSortOrder)),
        rows = todoTasks.map(_.srcId).map(gridRow(_)),
        cols = List(
          gridCol(
            colKey = "drag", width = boundGridColWidth(1,1),
            hideWill = 0,
          ),
          gridCol(
            colKey = "comments", width = boundGridColWidth(10,20),
            hideWill = 0,
          ),
          gridCol(
            colKey = "remove", width = boundGridColWidth(1,1),
            hideWill = 0,
          ),
        ),
        children = List(
          gridCell(
            colKey = "comments",
            rowKey = "head",
            classNames = HeaderCSSClassName :: Nil,
            children = List(
              exampleTags.text("text","Comments").toChildPair[OfDiv]
            )
          )
        ) ::: todoTasks.flatMap(task=>List(
          gridCell(
            colKey = "drag",
            rowKey = task.srcId,
            dragHandle = rowDragHandle,
            children = List(exampleTags.text("text","o").toChildPair[OfDiv]),
            classNames = DragHandleCellCSSClassName :: Nil
          ),
          gridCell(
            colKey = "comments",
            rowKey = task.srcId,
            children = List(
              exampleTags.input("field",
                comments.get(task.srcId).fold("")(_.value),
                onChangeReceiverFactory.create(task.srcId, TodoTaskComments),
              ).toChildPair[OfDiv]
            )
          ),
          gridCell(
            colKey = "remove",
            rowKey = task.srcId,
            children = List(exampleTags.button("text",
              caption = "x",
              activate = simpleReceiverFactory.create(delete(task))
            ).toChildPair[OfDiv])
          ),
        ))
      ).toChildPair[OfDiv]
    )
    logger.info("view")
    List(popupManager("pm",
      highlighter("col-highlighter",rowHighlightByAttr).toChildPair[OfDiv] ::
      highlighter("row-highlighter",colHighlightByAttr).toChildPair[OfDiv] ::
      list
    ).toChildPair[OfDiv])
  }
}

case object TaskNoReceiver extends Receiver[Context] {
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