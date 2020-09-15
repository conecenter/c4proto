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
import ee.cone.c4vdom._
import okio.ByteString



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

@c4("TestTodoApp") final class ExampleJsonAdapterProvider(util: TagJsonUtils) {
  @provide def int: Seq[JsonPairAdapter[Int]] =
    List(util.jsonPairAdapter((value,builder) => builder.just.append(value.toString)))//?DecimalFormat
  @provide def receiver: Seq[JsonPairAdapter[Receiver[Context]]] = List(new JsonPairAdapter[Receiver[Context]]{
    def appendJson(key: String, value: Receiver[Context], builder: MutableJsonBuilder): Unit = {}
  })
}

trait VExampleField
trait VExampleRow
trait VExampleList

@c4tags("TestTodoApp") trait ExampleTags {
  def field(key: String, value: String, change: Receiver[Context], caption: String): VDom[VExampleField]
  def row(key: String, remove: Receiver[Context])(cells: VDom[VExampleField]*): VDom[VExampleRow]
  @c4tag("ExampleList") def list(
    key: String, add: Receiver[Context], sort: SortReceiver
  )(filters: VDom[VExampleField]*)(rows: VDom[VExampleRow]*): VDom[VExampleList]
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

@protocol("TestTodoApp") object TestTodoProtocol {
  @Id(0x0001) case class B_TodoTask(@Id(0x0002) srcId: String, @Id(0x0003) createdAt: Long)
  @Id(0x0003) case class B_TodoTaskComments(@Id(0x0002) srcId: String, @Id(0x0003) value: String)
  @Id(0x0002) case class B_TodoTaskOrder(@Id(0x0002) srcId: SrcId, @Id(0x0005) order: List[SrcId])
  @Id(0x0004) case class B_TodoTaskCommentsContains(@Id(0x0002) srcId: SrcId, @Id(0x0003) value: String)
}

case object TodoTaskComments extends SetField(B_TodoTaskComments)
case object TodoTaskCommentsContains extends SetField(B_TodoTaskCommentsContains)

@c4("TestTodoApp") final case class TestTodoRootView(locationHash: String = "todo")(
  todoTags: ExampleTags,
  onChangeReceiverFactory: OnChangeReceiverFactory,
  simpleReceiverFactory: SimpleReceiverFactory,
  taskListAddReceiver: TaskListAddReceiver,
  sortTags: SortTags,
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
    val taskLines = for {
      prod <- todoTasks
    } yield todoTags.row(prod.srcId, remove = simpleReceiverFactory.create(delete(prod)))(
      todoTags.field("comments",
        value = comments.get(prod.srcId).fold("")(_.value),
        change = onChangeReceiverFactory.create(prod.srcId, TodoTaskComments),
        caption = "Comments",
      )
    )
    List(todoTags.list(listKey,
      add = taskListAddReceiver,
      sort = sortTags.toReceiver(taskLines.map(_.key),todoSortHandlerFactory.create(todoSortOrder)),
    )(
      todoTags.field("comments",
        value = commentsContainsValue,
        change = onChangeReceiverFactory.create(listKey, TodoTaskCommentsContains),
        caption = "Comments contains",
      )
    )(taskLines:_*))
  }
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