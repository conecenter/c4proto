package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.BranchTypes.BranchKey
import ee.cone.c4actor.LEvent.{add, delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, WorldKey, assemble, by}
import ee.cone.c4gate.TestTodoProtocol.TodoTask
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4vdom.ChildPair


object TestTodo extends Main((new TestTodoApp).execution.run)

class TestTodoApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp
  with VDomSSEApp
{
  lazy val testTags = new TestTags[World](childPairFactory,tagJsonUtils)
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

case class TestTodoRootView(branchKey: SrcId)/*(tags: TestTags[World])*/ extends View {
  def view: World ⇒ List[ChildPair[_]] = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val tags = TestTagsKey.of(local).get
    val mTags = TagsKey.of(local).get
    val world = TxKey.of(local).world
    val todoTasks = By.srcId(classOf[TodoTask]).of(world).values.flatten.toList.sortBy(-_.createdAt)
    val taskLines = todoTasks.map { task =>
      tags.div(
        task.srcId,
        List(
          tags.input("comments", task.comments,
            value ⇒ add(update(task.copy(comments=value)))
            //value ⇒ (task:Task) ⇒ task.copy(comments=value)
          ),
          tags.button("remove", "-", add(delete(task)))
        )
      )
    }
    val btnList = List(
      tags.button("add", "+",
        add(update(TodoTask(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )
    )
    List(btnList,taskLines).flatten
  }
}

