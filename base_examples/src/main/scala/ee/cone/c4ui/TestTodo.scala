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
import ee.cone.c4gate.{AuthOperations, CurrentSessionKey, PublishFromStringsProvider, SessionListUtil}
import ee.cone.c4ui.TestTodoProtocol.{B_TodoTask, B_TodoTaskComments, B_TodoTaskCommentsContains}
import ee.cone.c4proto._
import ee.cone.c4ui.TestCanvasProtocol.B_TestFigure
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._

@c4("ReactHtmlApp") final class ReactHtmlProvider extends PublishFromStringsProvider {
  def get: List[(String, String)] = {
    val now = System.currentTimeMillis
    List(
      "/ws-app.html" -> (
        """<!DOCTYPE html><meta charset="UTF-8">""" +
        s"""<body><script  type="module" src="/ws-app.js?$now"></script></body>"""
      ),
    )
  }
}

////////////////////////////////////////////////////////////////////////////////

object TestUser {
  val name = "test0"
}

@c4assemble("TestTodoApp") class TestUserAssembleBase(factory: TestUserCreateTxFactory){
  type ByUserName = String
  def map(key: SrcId, firstborn: Each[S_Firstborn]): Values[(ByUserName, S_Firstborn)] = List(TestUser.name->firstborn)
  def join(
    key: SrcId, @by[ByUserName] firstborn: Each[S_Firstborn], hashes: Values[C_PasswordHashOfUser]
  ): Values[(SrcId, TxTransform)] = if(hashes.nonEmpty) Nil else List(WithPK(factory.create()))
}

@c4multi("TestTodoApp") final case class TestUserCreateTx(srcId: SrcId = "TestUserCreateTx")(
  txAdd: LTxAdd, authOperations: AuthOperations
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val pass = TestUser.name
    logger.info(s"creating ${TestUser.name}")
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
case class TodoTasks(srcId: SrcId, tasks: List[TodoTask])
@c4assemble("TestTodoApp") class TodoTaskAssembleBase{
  def joinTask(key: SrcId, task: Each[B_TodoTask], comments: Values[B_TodoTaskComments]): Values[(SrcId, TodoTask)] =
    List(WithPK(TodoTask(task, comments.map(_.value).mkString)))

  type ToList = SrcId
  def mapTask(key: SrcId, task: Each[TodoTask]): Values[(ToList, TodoTask)] = List(TodoTasks.listKey->task)
  def joinList(key: SrcId, @by[ToList] tasks: Values[TodoTask]): Values[(SrcId, TodoTasks)] =
    List(WithPK(TodoTasks(key, tasks.sortBy(-_.orig.createdAt).toList)))
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
  getTodoTasks: GetByPK[TodoTasks], getTodoTaskCommentsContains: GetByPK[B_TodoTaskCommentsContains],
  exampleTagsProvider: ExampleTagsProvider,
)(
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context]
) extends ByLocationHashView with ViewUpdater with LazyLogging {
  import TestTodoRootView._
  import TodoTasks.listKey
  val rc: ViewAction => Receiver[Context] = updatingReceiverFactory.create(this, _)
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    val tasksOpt = getTodoTasks.ofA(local).get(listKey)
    val branchKey = CurrentBranchKey.of(local)
    val commentsContainsValue = getTodoTaskCommentsContains.ofA(local).get(branchKey).fold("")(_.value)
    val res = exampleTags.todoTaskList(
      key = listKey,
      commentsContainsValue, commentsFilterChange = rc(CommentsContainsChange(branchKey)),
      add = rc(Add()),
      tasks = tasksOpt.fold(List.empty[TodoTask])(_.tasks)
        .filter(_.comments.contains(commentsContainsValue))
        .map{ task => exampleTags.todoTask(
          key = task.orig.srcId, commentsValue = task.comments, commentsChange = rc(CommentsChange(task.orig.srcId)),
          remove = rc(Remove(task.orig)),
        )}
    )
    List(res.toChildPair)
  }
  def receive: Handler = value => _ => {
    case CommentsContainsChange(id) => update(B_TodoTaskCommentsContains(id, value))
    case Add() => update(B_TodoTask(UUID.randomUUID.toString,System.currentTimeMillis))
    case CommentsChange(id) =>
      //Thread.sleep(500)
      update(B_TodoTaskComments(id, value))
    case Remove(task) => delete(task)
  }
}
object TestTodoRootView {
  private case class CommentsContainsChange(srcId: String) extends ViewAction
  private case class Add() extends ViewAction
  private case class CommentsChange(srcId: String) extends ViewAction
  private case class Remove(task: B_TodoTask) extends ViewAction
}

////////////////////////////

trait TestSessionEl extends ToChildPair
@c4tags("TestTodoApp") trait TestSessionListTags[C] {
  @c4el("TestSession") def session(key: String, branchKey: String, userName: String, isOnline: Boolean): TestSessionEl
  @c4el("TestSessionList") def sessionList(key: String, sessions: ElList[TestSessionEl]): ToChildPair
}

@c4("TestTodoApp") final case class TestCoLeaderView(locationHash: String = "leader")(
  untilPolicy: UntilPolicy, sessionListUtil: SessionListUtil,
  testSessionListTagsProvider: TestSessionListTagsProvider,
)(
  tags: TestSessionListTags[Context] = testSessionListTagsProvider.get[Context]
) extends ByLocationHashView with LazyLogging {
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    val sessionItems = for {
      s <- sessionListUtil.list(local) if !s.location.endsWith(s"#${locationHash}")
    } yield tags.session(s.branchKey, s.branchKey, s.userName, s.isOnline)
    val res = tags.sessionList("sessionList", sessionItems.toList)
    List(res.toChildPair)
  }
}

////////////////////////////

@protocol("TestTodoApp") object TestCanvasProtocol   {
  @Id(0x0008) case class B_TestCanvasState(@Id(0x0009) srcId: String, @Id(0x000A) sizes: String)
  @Id(0x0009) case class B_TestFigure(@Id(0x0009) srcId: String, @Id(0x000B) isActive: Boolean)
}
import TestCanvasProtocol.B_TestCanvasState

trait ExampleFigureEl extends ToChildPair
@c4tags("TestTodoApp") trait ExampleCanvasTags[C] {
  @c4el("ExampleFigure") def figure(key: String, offset: Int, activate: Receiver[C], isActive: Boolean): ExampleFigureEl
  @c4el("ExampleCanvas") def canvas(
    key: String, sizesValue: String, sizesChange: Receiver[C], figures: ElList[ExampleFigureEl]
  ): ToChildPair
}

@c4("TestTodoApp") final case class TestCanvasView(locationHash: String = "rectangle")(
  updatingReceiverFactory: UpdatingReceiverFactory,
  untilPolicy: UntilPolicy,
  getTestCanvasState: GetByPK[B_TestCanvasState], getTestFigure: GetByPK[B_TestFigure],
  exampleCanvasTagsProvider: ExampleCanvasTagsProvider,
)(
  exampleCanvasTags: ExampleCanvasTags[Context] =  exampleCanvasTagsProvider.get[Context]
) extends ByLocationHashView with ViewUpdater {
  import TestCanvasView._
  val rc: ViewAction => Receiver[Context] = updatingReceiverFactory.create(this, _)
  private def figure(local: Context, key: String, pos: Int) = {
    val isActive = getTestFigure.ofA(local).get(key).exists(_.isActive)
    exampleCanvasTags.figure(key, pos, rc(Activate(key, !isActive)), isActive)
  }
  def view: Context => ViewRes = untilPolicy.wrap { local =>
    val sessionKey = CurrentSessionKey.of(local)
    val sizes = getTestCanvasState.ofA(local).get(sessionKey).fold("")(_.sizes)
    val res = exampleCanvasTags.canvas("testCanvas", sizes, rc(SizesChange(sessionKey)), List(
      figure(local,"0",0), figure(local,"1",50)
    ))
    List(res.toChildPair)
  }
  def receive: Handler = value => _ => {
    case SizesChange(sessionKey) => update(B_TestCanvasState(sessionKey, value))
    case Activate(id, value) => update(B_TestFigure(id, value))
  }
}
object TestCanvasView {
  private case class SizesChange(sessionKey: String) extends ViewAction
  private case class Activate(id: String, value: Boolean) extends ViewAction
}