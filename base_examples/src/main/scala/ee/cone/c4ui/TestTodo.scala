package ee.cone.c4ui

import java.util.UUID
import okio.ByteString
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4actor._
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.PublishFromStringsProvider
import ee.cone.c4ui.TestTodoProtocol.{B_TodoTask, B_TodoTaskComments, B_TodoTaskCommentsContains}
import ee.cone.c4proto._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._

trait Updater {
  type Handler = VDomMessage => Context => LEvents
  def receive: Handler
}
@c4multi("TestTodoApp") final case class UpdatingReceiver(updater: Updater)(txAdd: LTxAdd) extends Receiver[Context] {
  def receive: Handler = m => local => txAdd.add(updater.receive(m)(local))(local)
}

abstract class SetField[T](val make: (SrcId,String)=>T) extends Product
case class OnChangeReceiver[T<:Product](srcId: SrcId, make: SetField[T]) extends Updater {
  def receive: Handler = message => _ => {
    val value = message.body match { case b: ByteString => b.utf8() }
    update(make.make(srcId,value))
  }
}
case class SimpleReceiver(events: Seq[LEvent[Product]]) extends Updater {
  def receive: Handler = _ => _ => events
}

trait ViewUpdater extends Product {
  type Handler = String => Context => ViewAction => LEvents
}
trait ViewAction extends Product

@c4("ReactHtmlApp") final class ReactHtmlProvider extends PublishFromStringsProvider {
  def get: List[(String, String)] = {
    val now = System.currentTimeMillis
    List(
      /*
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
*/
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
  //@Id(0x0002) case class B_TodoTaskOrder(@Id(0x0002) srcId: SrcId, @Id(0x0005) order: List[SrcId])
  @Id(0x0004) case class B_TodoTaskCommentsContains(@Id(0x0002) srcId: SrcId, @Id(0x0003) value: String)
}

trait TodoTaskEl extends ToChildPair
@c4tags("TestTodoApp") trait ExampleTags[C] {
  @c4el("ExampleTodoTask") def todoTask(
    key: String, commentsValue: String, commentsChange: Receiver[C], remove: Receiver[C]
  ): TodoTaskEl
  @c4el("ExampleTodoTasks") def todoTasks(
    key: String, commentsFilterValue: String, commentsFilterChange: Receiver[C], add: Receiver[C],
    tasks: ElList[TodoTaskEl]
  ): ToChildPair
}

@c4("TestTodoApp") final case class TestTodoRootView(locationHash: String = "todo")(
  updatingReceiverFactory: UpdatingReceiverFactory,
  untilPolicy: UntilPolicy,
  getB_TodoTask: GetByPK[B_TodoTask],
  getB_TodoTaskComments: GetByPK[B_TodoTaskComments],
  getB_TodoTaskCommentsContains: GetByPK[B_TodoTaskCommentsContains],
  exampleTagsProvider: ExampleTagsProvider,
)(
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context]
) extends ByLocationHashView with ViewUpdater with LazyLogging {
  import TestTodoRootView._
  def rc: ViewAction => Receiver[Context] = updatingReceiverFactory.create
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    val listKey = "taskList"
    val commentsContainsValue = getB_TodoTaskCommentsContains.ofA(local).get(listKey).fold("")(_.value)
    val comments = getB_TodoTaskComments.ofA(local)
    val res = exampleTags.todoTasks(
      key = listKey,
      commentsFilterValue = commentsContainsValue, commentsFilterChange = rc(CommentsContainsChange(listKey)),
      add = rc(Add()),
      tasks = getB_TodoTask.ofA(local).values.map(task => (task,comments.get(task.srcId).fold("")(_.value)))
        .filter{ case (_, comment) => comment.contains(commentsContainsValue) }
        .toList.sortBy{ case (task, _) => -task.createdAt }
        .map{ case (task, comment) =>
          exampleTags.todoTask(
            key = task.srcId,
            commentsValue = comment, commentsChange = rc(CommentsChange(task.srcId)), remove = rc(Remove(task)),
          )
        }
    )
    List(res.toChildPair)
  }
  def receive: Handler = value => _ => {
    case CommentsContainsChange(id) => update(B_TodoTaskCommentsContains(id, value))
    case Add() => update(B_TodoTask(UUID.randomUUID.toString,System.currentTimeMillis))
    case CommentsChange(id) => update(B_TodoTaskComments(id, value))
    case Remove(task) => delete(task)
  }
}
object TestTodoRootView {
  private case class CommentsContainsChange(srcId: String) extends ViewAction
  private case class Add() extends ViewAction
  private case class CommentsChange(srcId: String) extends ViewAction
  private case class Remove(task: B_TodoTask) extends ViewAction
}


    // filter minWidth = 11, maxWidth = 20,"Comments contains"
    //  "+"
    //dragRow = sortReceiverFactory.create(todoSortHandlerFactory.create(todoSortOrder)),
//    colKey = "drag", width = boundGridColWidth(1,1),
//    colKey = "comments", width = boundGridColWidth(10,20),
//    colKey = "remove", width = boundGridColWidth(1,1),
    /*
    exampleTags.input("field",

    ).toChildPair[OfDiv]
      children = List(exampleTags.button("text",
      caption = "x",
      activate =
    ).toChildPair[OfDiv])*/

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
