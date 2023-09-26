package ee.cone.c4ui

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di._
import ee.cone.c4actor._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.ViewRes

@c4("TestTodoApp") final case class ReplicaListRootView(locationHash: String = "replicas")(
  untilPolicy: UntilPolicy,
  exampleTagsProvider: ExampleTagsProvider,
  listTagsProvider: ListTagsProvider,
  getReadyProcesses: GetByPK[ReadyProcesses],
  actorName: ActorName,
  simpleReceiverFactory: SimpleReceiverFactory,
  replicaCompleteReceiverFactory: ReplicaCompleteReceiverFactory,
)(
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context],
  listTags: ListTags[Context] = listTagsProvider.get[Context],
) extends ByLocationHashView {
  import listTags._
  def view: Context => ViewRes = untilPolicy.wrap { local =>
    val processes = getReadyProcesses.ofA(local).get(actorName.value).fold(List.empty[ReadyProcess])(_.all)
    val rows = processes.map(_.id).map(gridRow(_))
    val cols = List(
      gridCol(colKey = "role", width = boundGridColWidth(5, 10), hideWill = 0),
      gridCol(colKey = "startedAt", width = boundGridColWidth(10, 20), hideWill = 0),
      gridCol(colKey = "hostname", width = boundGridColWidth(10, 20), hideWill = 0),
      gridCol(colKey = "version", width = boundGridColWidth(10, 20), hideWill = 0),
      gridCol(colKey = "completion", width = boundGridColWidth(5, 20), hideWill = 0),
      gridCol(colKey = "remove", width = boundGridColWidth(5, 10), hideWill = 0),
    )
    val headCells = for {
      (key,text) <- List(
        ("role","Role"),("startedAt","Started At"),("hostname","Hostname"),("version","Version"),("completion","Completion")
      )
    } yield gridCell(
      colKey = key, rowKey = "head", classNames = HeaderCSSClassName :: Nil,
      children = List(exampleTags.text("text", text).toChildPair[OfDiv])
    )
    val bodyCells = for {
      p <- processes
      cell <- List(
        gridCell(
          colKey = "role", rowKey = p.id,
          children = List(exampleTags.text("text", p.role).toChildPair[OfDiv])
        ),
        gridCell(
          colKey = "startedAt", rowKey = p.id,
          children = List(exampleTags.text("text",
            Instant.ofEpochMilli(p.startedAt).toString
          ).toChildPair[OfDiv])
        ),
        gridCell(
          colKey = "hostname", rowKey = p.id,
          children = List(exampleTags.text("text", p.hostname).toChildPair[OfDiv])
        ),
        gridCell(
          colKey = "version", rowKey = p.id,
          children = List(exampleTags.text("text", p.refDescr).toChildPair[OfDiv])
        ),
        gridCell(
          colKey = "completion", rowKey = p.id,
          children = p.completionReqAt.toList.map(at=>exampleTags.text("text", at.toString).toChildPair[OfDiv])
        ),
        gridCell(
          colKey = "remove", rowKey = p.id,
          children = List(
            exampleTags.button(
              "complete", activate = replicaCompleteReceiverFactory.create(p), caption = "x..."
            ).toChildPair[OfDiv],
            exampleTags.button(
              "force-remove", activate = simpleReceiverFactory.create(p.halt), caption = "x("
            ).toChildPair[OfDiv],
          )
        ),
      )
    } yield cell
    List(gridRoot("replicaList",
      dragCol = TaskNoReceiver, dragRow = TaskNoReceiver, rows = rows, cols = cols, children = headCells ::: bodyCells
    ).toChildPair[OfDiv])
  }
}

@c4multi("TestTodoApp") final case class ReplicaCompleteReceiver(process: ReadyProcess)(
  txAdd: LTxAdd
) extends Receiver[Context] {
  def receive: Handler = message => local => txAdd.add(process.complete(Instant.now.plusSeconds(5)))(local)
}

@c4("TestTodoApp") final case class ReplicaBadShutdown(execution: Execution) extends Executable with LazyLogging {
  def run(): Unit = {
    logger.info("installing bad hook for master")
    val ignoreRemove = execution.onShutdown("Bad",() => Thread.sleep(10000))
  }
}
