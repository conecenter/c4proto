package ee.cone.c4ui

import java.util.UUID
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.AuthProtocol.C_PasswordHashOfUser
import ee.cone.c4gate.{AuthOperations, PublishFromStringsProvider}
import ee.cone.c4ui.TestTodoProtocol.{B_TodoTask, B_TodoTaskComments, B_TodoTaskCommentsContains}
import ee.cone.c4proto._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._

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
        s"""<body><script  type="module" src="/src/c4p/test/ws-app.jsx?$now"></script></body>"""
      ),

    )
  }
}

object TestUser {
  val name = "test"
}

@c4assemble("TestTodoApp") class TestUserAssembleBase(factory: TestUserCreateTxFactory){
  private type ByUserName = String
  def map(key: SrcId, firstborn: Each[S_Firstborn]): Values[(ByUserName, S_Firstborn)] = List(TestUser.name->firstborn)
  def join(
    key: SrcId, @by[ByUserName] firstborn: Each[S_Firstborn], hashes: Values[C_PasswordHashOfUser]
  ): Values[(SrcId, TxTransform)] = if(hashes.nonEmpty) Nil else List(WithPK(factory.create()))
}

@c4multi("TestTodoApp") final case class TestUserCreateTx(srcId: SrcId = "TestUserCreateTx")(
  txAdd: LTxAdd, authOperations: AuthOperations
) extends TxTransform {
  def transform(local: Context): Context = {
    val pass = TestUser.name
    val lEvents = update(C_PasswordHashOfUser(TestUser.name, Option(authOperations.createHash(pass, None))))
    txAdd.add(lEvents)(local)
  }
}

////////////////////////////////////////////////////////////////////////////////

@protocol("TestTodoApp") object TestTodoProtocol {
  @Id(0x0001) case class B_TodoTask(@Id(0x0002) srcId: String, @Id(0x0003) createdAt: Long)
  @Id(0x0003) case class B_TodoTaskComments(@Id(0x0002) srcId: String, @Id(0x0003) value: String)
  //@Id(0x0002) case class B_TodoTaskOrder(@Id(0x0002) srcId: SrcId, @Id(0x0005) order: List[SrcId])
  @Id(0x0004) case class B_TodoTaskCommentsContains(@Id(0x0002) srcId: SrcId, @Id(0x0003) value: String)
}

object TodoTasks {
  def listKey = "taskList"
}
case class TodoTask(orig: B_TodoTask, comments: String)
case class TodoTasks(srcId: SrcId, commentsContains: String, tasks: List[TodoTask])
@c4assemble("TestTodoApp") class TodoTaskAssembleBase{
  def joinTask(key: SrcId, task: Each[B_TodoTask], comments: Values[B_TodoTaskComments]): Values[(SrcId, TodoTask)] =
    List(WithPK(TodoTask(task, comments.map(_.value).mkString)))

  type ToList = SrcId
  def mapTask(key: SrcId, task: Each[TodoTask]): Values[(ToList, TodoTask)] = List(TodoTasks.listKey->task)
  def joinList(
    key: SrcId, @by[ToList] tasks: Values[TodoTask], filters: Values[B_TodoTaskCommentsContains]
  ): Values[(SrcId, TodoTasks)] = {
    val commentsContainsValue = filters.map(_.value).mkString
    val filteredTasks = tasks.filter(_.comments.contains(commentsContainsValue)).sortBy(-_.orig.createdAt).toList
    List(WithPK(TodoTasks(key, commentsContainsValue, filteredTasks)))
  }
}

trait TodoTaskEl extends ToChildPair
@c4tags("TestTodoApp") trait ExampleTags[C] {
  @c4el("ExampleTodoTask") def todoTask(
    key: String, commentsValue: String, commentsChange: Receiver[C], remove: Receiver[C]
  ): TodoTaskEl
  @c4el("ExampleTodoTaskList") def todoTaskList(
    key: String, commentsFilterValue: String, commentsFilterChange: Receiver[C], add: Receiver[C],
    tasks: ElList[TodoTaskEl]
  ): ToChildPair
}

@c4("TestTodoApp") final case class TestTodoRootView(locationHash: String = "todo")(
  updatingReceiverFactory: UpdatingReceiverFactory,
  untilPolicy: UntilPolicy,
  getTodoTasks: GetByPK[TodoTasks],
  exampleTagsProvider: ExampleTagsProvider,
)(
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context]
) extends ByLocationHashView with ViewUpdater with LazyLogging {
  import TestTodoRootView._
  import TodoTasks.listKey
  val rc: ViewAction => Receiver[Context] = updatingReceiverFactory.create(this, _)
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    val tasksOpt = getTodoTasks.ofA(local).get(listKey)
    val res = exampleTags.todoTaskList(
      key = listKey,
      commentsFilterValue = tasksOpt.fold("")(_.commentsContains),
      commentsFilterChange = rc(CommentsContainsChange(listKey)),
      add = rc(Add()),
      tasks = tasksOpt.fold(List.empty[TodoTask])(_.tasks).map{ task => exampleTags.todoTask(
        key = task.orig.srcId, commentsValue = task.comments, commentsChange = rc(CommentsChange(task.orig.srcId)),
        remove = rc(Remove(task.orig)),
      )}
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
