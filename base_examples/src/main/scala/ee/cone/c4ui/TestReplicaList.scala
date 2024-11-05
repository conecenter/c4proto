package ee.cone.c4ui

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di._
import ee.cone.c4actor._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.{ElList, ViewRes}

trait ReplicaEl extends ToChildPair
@c4tags("TestTodoApp") trait ExampleReplicaTags[C] {
  @c4el("ExampleReplica") def replica(
    key: String,
    role: String, startedAt: String, hostname: String, version: String, completion: String,
    complete: Receiver[C], forceRemove: Receiver[C]
  ): TodoTaskEl
  @c4el("ExampleReplicas") def replicas(key: String, replicas: ElList[ReplicaEl]): ToChildPair
}

@c4("TestTodoApp") final case class ReplicaListRootView(locationHash: String = "replicas")(
  untilPolicy: UntilPolicy,
  exampleTagsProvider: ExampleReplicaTagsProvider,
  getReadyProcesses: GetByPK[ReadyProcesses],
  actorName: ActorName,
  updatingReceiverFactory: UpdatingReceiverFactory,
)(
  tags: ExampleReplicaTags[Context] = exampleTagsProvider.get[Context],
) extends ByLocationHashView {
  def rc: Updater => UpdatingReceiver = updatingReceiverFactory.create
  def view: Context => ViewRes = untilPolicy.wrap { local =>
    val processes = getReadyProcesses.ofA(local).get(actorName.value).fold(List.empty[ReadyProcess])(_.all)
    val res = tags.replicas("replicas", for(p <- processes) yield tags.replica(
      key = p.id, role = p.role, startedAt = Instant.ofEpochMilli(p.startedAt).toString, hostname = p.hostname,
      version = p.refDescr, completion = p.completionReqAt.fold("")(_.toString),
      complete = rc(ReplicaCompleteReceiver(p)), forceRemove = rc(SimpleReceiver(p.halt)),
    ))
    List(res.toChildPair)
  }
}

case class ReplicaCompleteReceiver(process: ReadyProcess) extends Updater {
  def receive: Handler = _ => _ => process.complete(Instant.now.plusSeconds(5))
}

@c4("TestTodoApp") final case class ReplicaBadShutdown(execution: Execution) extends Executable with LazyLogging {
  def run(): Unit = {
    logger.info("installing bad hook for master")
    val ignoreRemove = execution.onShutdown("Bad",() => Thread.sleep(10000))
  }
}

/*
val cols = List(
  gridCol(colKey = "role", width = boundGridColWidth(5, 10), hideWill = 0),
  gridCol(colKey = "startedAt", width = boundGridColWidth(10, 20), hideWill = 0),
  gridCol(colKey = "hostname", width = boundGridColWidth(10, 20), hideWill = 0),
  gridCol(colKey = "version", width = boundGridColWidth(10, 20), hideWill = 0),
  gridCol(colKey = "completion", width = boundGridColWidth(5, 20), hideWill = 0),
  gridCol(colKey = "remove", width = boundGridColWidth(5, 10), hideWill = 0),
)
("role","Role"),("startedAt","Started At"),("hostname","Hostname"),("version","Version"),("completion","Completion")
"complete" "x..."
"force-remove" "x("
 */