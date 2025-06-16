package ee.cone.c4ui

import java.util.UUID
import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.AuthProtocol.{C_PasswordHashOfUser, U_AuthenticatedSession}
import ee.cone.c4gate._
import ee.cone.c4ui.TestTodoProtocol.{B_TodoTask, B_TodoTaskComments, B_TodoTaskCommentsContains}
import ee.cone.c4proto._
import ee.cone.c4ui.TestCanvasProtocol.B_TestFigure
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._

////

trait ExampleMenuItemEl extends ToChildPair
@c4tags("TestTodoApp") trait ExampleMenuTags[C] {
  @c4el("ExampleLogin") def login(key: String): ToChildPair
  @c4el("ExampleMenu") def menu(key: String, items: ElList[ExampleMenuItemEl], children: ViewRes): ToChildPair
  @c4el def menuItem(key: String, caption: String, activate: Receiver[C]): ExampleMenuItemEl
}

@c4("TestTodoApp") final case class WrapView()(
  untilPolicy: UntilPolicy, sessionUtil: SessionUtil, getSession: GetByPK[U_AuthenticatedSession],
  val rc: UpdatingReceiverFactory, toAlienMessageUtil: ToAlienMessageUtil, locationUtil: LocationUtil,
  exampleMenuTagsProvider: ExampleMenuTagsProvider,
)(
  exampleMenuTags: ExampleMenuTags[Context] = exampleMenuTagsProvider.get[Context]
) extends Updater {
  import WrapView._
  def wrap(view: Context=>ViewRes): Context=>ViewRes = untilPolicy.wrap{ local =>
    val sessionKey = CurrentSessionKey.of(local)
    val hasLogin = getSession.ofA(local).get(sessionKey).exists(_.userName.nonEmpty)
    val res = if(hasLogin) exampleMenuTags.menu(
      "menu",
      (for((key,caption) <- List(
        ("todo", "todo-list"), ("leader", "coworking"), ("rectangle", "canvas"), ("revert", "reverting"),
        ("replicas", "replicas"),
      )) yield exampleMenuTags.menuItem(s"menu-item-${key}", caption, rc(GoTo(sessionKey,key)))) ++ List(
        exampleMenuTags.menuItem("sendTestMessage", "test message", rc(SendTestMessage(sessionKey))),
        exampleMenuTags.menuItem("logout", "logout", rc(LogOut(sessionKey))),
      ),
      view(local)
    ) else exampleMenuTags.login("login")
    List(res.toChildPair)
  }
  def receive: Handler = _ => local => {
    case GoTo(sessionKey, to) => locationUtil.setLocationHash(local, sessionKey, to)
    case SendTestMessage(sessionKey) => toAlienMessageUtil.create(sessionKey, UUID.randomUUID().toString)
    case LogOut(sessionKey) => sessionUtil.logOut(local, sessionKey)
  }
}
object WrapView {
  private case class GoTo(sessionKey: String, hash: String) extends VAction
  private case class SendTestMessage(sessionKey: String) extends VAction
  private case class LogOut(sessionKey: String) extends VAction
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
  @c4el def todoTask(key: String, commentsValue: String, commentsChange: Receiver[C], remove: Receiver[C]): TodoTaskEl
  @c4el("ExampleTodoTaskList") def todoTaskList(
    key: String, commentsFilterValue: String, commentsFilterChange: Receiver[C], add: Receiver[C],
    tasks: ElList[TodoTaskEl]
  ): ToChildPair
}

@c4("TestTodoApp") final case class TestTodoRootView(locationHash: String = "todo")(
  val rc: UpdatingReceiverFactory,
  wrapView: WrapView,
  getTodoTasks: GetByPK[TodoTasks], getTodoTaskCommentsContains: GetByPK[B_TodoTaskCommentsContains],
  exampleTagsProvider: ExampleTagsProvider,
)(
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context]
) extends ByLocationHashView with Updater with LazyLogging {
  import TestTodoRootView._
  import TodoTasks.listKey
  def view: Context => ViewRes = wrapView.wrap{ local =>
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
  private case class CommentsContainsChange(srcId: String) extends VAction
  private case class Add() extends VAction
  private case class CommentsChange(srcId: String) extends VAction
  private case class Remove(task: B_TodoTask) extends VAction
}

////////////////////////////

trait TestSessionEl extends ToChildPair
@c4tags("TestTodoApp") trait TestSessionListTags[C] {
  @c4el def session(key: String, branchKey: String, userName: String, isOnline: Boolean): TestSessionEl
  @c4el("TestSessionList") def sessionList(key: String, sessions: ElList[TestSessionEl]): ToChildPair
}

@c4("TestTodoApp") final case class TestCoLeaderView(locationHash: String = "leader")(
  wrapView: WrapView, sessionListUtil: SessionListUtil,
  testSessionListTagsProvider: TestSessionListTagsProvider,
)(
  tags: TestSessionListTags[Context] = testSessionListTagsProvider.get[Context]
) extends ByLocationHashView with LazyLogging {
  def view: Context => ViewRes = wrapView.wrap{ local =>
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
  @c4el def figure(key: String, offset: Int, activate: Receiver[C], isActive: Boolean): ExampleFigureEl
  @c4el("ExampleCanvas") def canvas(
    key: String, sizesValue: String, sizesChange: Receiver[C], figures: ElList[ExampleFigureEl]
  ): ToChildPair
}

@c4("TestTodoApp") final case class TestCanvasView(locationHash: String = "rectangle")(
  val rc: UpdatingReceiverFactory, wrapView: WrapView,
  getTestCanvasState: GetByPK[B_TestCanvasState], getTestFigure: GetByPK[B_TestFigure],
  exampleCanvasTagsProvider: ExampleCanvasTagsProvider,
)(
  exampleCanvasTags: ExampleCanvasTags[Context] =  exampleCanvasTagsProvider.get[Context]
) extends ByLocationHashView with Updater {
  import TestCanvasView._
  private def figure(local: Context, key: String, pos: Int) = {
    val isActive = getTestFigure.ofA(local).get(key).exists(_.isActive)
    exampleCanvasTags.figure(key, pos, rc(Activate(key, !isActive)), isActive)
  }
  def view: Context => ViewRes = wrapView.wrap { local =>
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
  private case class SizesChange(sessionKey: String) extends VAction
  private case class Activate(id: String, value: Boolean) extends VAction
}

////////////////////////////

@c4tags("TestTodoApp") trait RevertRootViewTags[C] {
  @c4el("ExampleReverting") def reverting(key: String, revertToSavepoint: Receiver[C]): ToChildPair
}

@c4("TestTodoApp") final case class RevertRootView(locationHash: String = "revert")(
  wrapView: WrapView, revertRootViewTagsProvider: RevertRootViewTagsProvider, reducer: RichRawWorldReducer,
)(
  tags: RevertRootViewTags[Context] = revertRootViewTagsProvider.get[Context],
) extends ByLocationHashView with Updater {
  import RevertRootView._
  def view: Context => ViewRes = wrapView.wrap { local =>
    val res = tags.reverting(key = "reverting", revertToSavepoint = rc(Revert()))
    List(res.toChildPair)
  }
  def receive: Handler = _ => _ => {
    case Revert() => reducer.toRevertUpdates(l)
  }
}
object RevertRootView {
  private case class Revert() extends VAction
}

////////////////////////////

trait ReplicaEl extends ToChildPair
@c4tags("TestTodoApp") trait ExampleReplicaTags[C] {
  @c4el def replica(
    key: String,
    role: String, startedAt: String, hostname: String, version: String, completion: String,
    complete: Receiver[C], forceRemove: Receiver[C]
  ): ReplicaEl
  @c4el("ExampleReplicaList") def replicas(key: String, replicas: ElList[ReplicaEl]): ToChildPair
}

@c4("TestTodoApp") final case class ReplicaListRootView(locationHash: String = "replicas")(
  wrapView: WrapView,
  exampleTagsProvider: ExampleReplicaTagsProvider,
  readyProcessUtil: ReadyProcessUtil,
  val rc: UpdatingReceiverFactory,
)(
  tags: ExampleReplicaTags[Context] = exampleTagsProvider.get[Context],
) extends ByLocationHashView with Updater {
  import ReplicaListRootView._
  def view: Context => ViewRes = wrapView.wrap { local =>
    val res = tags.replicas("replicas", for(p <- readyProcessUtil.getAll(local).all) yield tags.replica(
      key = p.id, role = p.role, startedAt = Instant.ofEpochMilli(p.startedAt).toString, hostname = p.hostname,
      version = p.refDescr, completion = p.completionReqAt.fold("")(_.toString), complete = rc(Complete(p)),
      forceRemove = rc(ForceRemove(p)),
    ))
    List(res.toChildPair)
  }
  def receive: Handler = _ => _ => {
    case Complete(p) => p.complete(Instant.now.plusSeconds(5))
    case ForceRemove(p) => p.halt
  }
}
object ReplicaListRootView {
  private case class Complete(process: ReadyProcess) extends VAction
  private case class ForceRemove(process: ReadyProcess) extends VAction
}

@c4("TestTodoApp") final case class ReplicaBadShutdown(execution: Execution) extends Executable with LazyLogging {
  def run(): Unit = {
    logger.info("installing bad hook for master")
    val ignoreRemove = execution.onShutdown("Bad",() => Thread.sleep(10000))
  }
}
