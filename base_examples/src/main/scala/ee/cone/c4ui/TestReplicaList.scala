package ee.cone.c4ui

import java.time.Instant
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
)(
  exampleTags: ExampleTags[Context] = exampleTagsProvider.get[Context],
  listTags: ListTags[Context] = listTagsProvider.get[Context],
) extends ByLocationHashView {
  import listTags._
  def view: Context => ViewRes = untilPolicy.wrap { local =>
    val processes = getReadyProcesses.ofA(local).get(actorName.value).fold(List.empty[ReadyProcess])(_.processes)
    val rows = processes.map(_.id).map(gridRow(_))
    val cols = List(
      gridCol(colKey = "startedAt", width = boundGridColWidth(10, 20), hideWill = 0),
      gridCol(colKey = "hostname", width = boundGridColWidth(10, 20), hideWill = 0),
      gridCol(colKey = "image", width = boundGridColWidth(10, 20), hideWill = 0),
      gridCol(colKey = "remove", width = boundGridColWidth(1, 1), hideWill = 0),
    )
    val headCells = for {
      (key,text) <- List(("startedAt","Started At"),("hostname","Hostname"),("image","Image"))
    } yield gridCell(
      colKey = key, rowKey = "head", classNames = HeaderCSSClassName :: Nil,
      children = List(exampleTags.text("text", text).toChildPair[OfDiv])
    )
    val bodyCells = for {
      p <- processes
      cell <- List(
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
          colKey = "image", rowKey = p.id,
          children = List(exampleTags.text("text", p.image).toChildPair[OfDiv])
        ),
        gridCell(
          colKey = "remove", rowKey = p.id,
          children = List(exampleTags.button(
            "remove", activate = simpleReceiverFactory.create(p.halt), caption = "x"
          ).toChildPair[OfDiv])
        ),
      )
    } yield cell
    List(gridRoot("replicaList",
      dragCol = TaskNoReceiver, dragRow = TaskNoReceiver, rows = rows, cols = cols, children = headCells ::: bodyCells
    ).toChildPair[OfDiv])
  }
}
