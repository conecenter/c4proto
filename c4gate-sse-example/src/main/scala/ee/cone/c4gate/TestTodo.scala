package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, fieldAccess}
import ee.cone.c4gate.TestTodoProtocol.TodoTask
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom.VDomLens

class TestTodoApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
  with UMLClientsApp with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
{
  override def protocols: List[Protocol] = TestTodoProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestTodoAssemble ::
    new FromAlienTaskAssemble("/react-app.html") ::
    super.assembles
  override def toInject: List[ToInject] =
    new TestMetaData(fieldMetaBuilder) ::
    super.toInject
}

@protocol object TestTodoProtocol extends Protocol {
  @Id(0x0001) case class TodoTask(
    @Id(0x0002) srcId: String,
    @Id(0x0003) createdAt: Long,
    @Id(0x0004) comments: String
  )
}

@fieldAccess class TestMetaData(meta: FieldMetaBuilder[Nothing,Nothing]) extends ToInject {
  private def task = meta model classOf[TodoTask]
  def toInject: List[Injectable] = List(
    task attr PlaceholderKey ofField (_.createdAt) set "(created at)",
    task attr PlaceholderKey ofField (_.comments) set "(comments)"
  ).flatten
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
    ) yield WithPK(view)
}


case class TestTodoRootView(branchKey: SrcId) extends View {
  def view: Context ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val tags = TestTagsKey.of(local)
    val mTags = TagsKey.of(local)
    val conductor = ModelAccessFactoryKey.of(local)
    import mTags._
    val todoTasks = ByPK(classOf[TodoTask]).of(local).values.toList.sortBy(-_.createdAt)
    val input = tags.input(local)
    @fieldAccess val taskLines = todoTasks.map { task ⇒
      conductor conducts task
      div(task.srcId,Nil)(
        (input boundTo task.comments) :::
          divButton("remove")(TxAdd(delete(task)))(List(text("caption","-"))) :: Nil
      )
    }
    val btnList = List(
      divButton("add")(
        TxAdd(update(TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
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