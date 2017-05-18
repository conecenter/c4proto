package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{add, delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4gate.TestTodoProtocol.TodoTask
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes

class TestTodoApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
  with UMLClientsApp
{
  override def protocols: List[Protocol] = TestTodoProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestTodoAssemble ::
    new FromAlienTaskAssemble("localhost", "/react-app.html") ::
    super.assembles
}

@protocol object TestTodoProtocol extends Protocol {
  @Id(0x0001) case class TodoTask(
    @Id(0x0002) srcId: String,
    @Id(0x0003) createdAt: Long,
    @Id(0x0004) comments: String
  )
}

@assemble class TestTodoAssemble extends Assemble {
  def joinView(
    key: SrcId,
    fromAliens: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      fromAlien ← fromAliens;
      view ← Option(fromAlien.locationHash).collect{
        case "todo" ⇒ TestTodoRootView(fromAlien.branchKey)
      }
    ) yield fromAlien.branchKey → view
}

case object TaskComments extends TextInputLens[TodoTask](_.comments,v⇒_.copy(comments=v))

case class TestTodoRootView(branchKey: SrcId) extends View {
  def view: World ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val tags = TestTagsKey.of(local).get
    val mTags = TagsKey.of(local).get
    import mTags._
    val world = TxKey.of(local).world
    val todoTasks = By.srcId(classOf[TodoTask]).of(world).values.flatten.toList.sortBy(-_.createdAt)
    val input = tags.toInput("comments", TaskComments)
    val taskLines = todoTasks.map { task =>
      div(task.srcId,Nil)(
        List(
          input(task),
          divButton("remove")(add(delete(task)))(List(text("caption","-")))
        )
      )
    }
    val btnList = List(
      divButton("add")(
        add(update(TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )(List(text("text","+")))
    )
    List(btnList,taskLines).flatten
  }
}

/*
branches:
    BranchResult --> BranchRel-s
    BranchResult [prev] + BranchRel-s --> BranchTask [decode]
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

BranchResult --> BranchRel-s --> BranchTask --> [custom] --> BranchHandler --> TxTransform
*/