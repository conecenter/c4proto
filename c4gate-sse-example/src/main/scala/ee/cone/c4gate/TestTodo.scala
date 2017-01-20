package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{add, delete, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.BranchTypes.LocationHash
import ee.cone.c4gate.TestTodoProtocol.Task
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4vdom.{ChildPair, RootView}
import ee.cone.c4vdom_impl.UntilElement

object TestTodo extends Main((new TestTodoApp).execution.run)

class TestTodoApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp
  with VDomSSEApp
{
  lazy val rootView: RootView[World] = {
    println(s"visit http://localhost:${config.get("C4HTTP_PORT")}/react-app.html")
    new TestTodoRootView(testTags)
  }
  lazy val testTags = new TestTags[World](childPairFactory,tagJsonUtils)
  override def protocols: List[Protocol] = TestTodoProtocol :: super.protocols
}

@protocol object TestTodoProtocol extends Protocol {
  @Id(0x0001) case class Task(
    @Id(0x0002) srcId: String,
    @Id(0x0003) createdAt: Long,
    @Id(0x0004) comments: String
  )
}

@assemble class TestTodoAssemble extends Assemble {
  def join(
    key: SrcId,
    @by[LocationHash] tasks: Values[BranchTask]
  ): Values[(SrcId,View)] =
    for(task ← tasks if key == "") task.branchKey →



}

class TestTodoRootView(tags: TestTags[World]) extends View {
  def view(local: World): List[ChildPair[_]] = {
    val startTime = System.currentTimeMillis
    val world = TxKey.of(local).world
    val tasks = By.srcId(classOf[Task]).of(world).values.flatten.toSeq.sortBy(-_.createdAt)
    val taskLines = tasks.map { task =>
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
        add(update(Task(UUID.randomUUID.toString,System.currentTimeMillis,"")))
      )
    )
    val res = List(btnList,taskLines).flatten
    val endTime = System.currentTimeMillis
    val until = endTime+(endTime-startTime)*10
    child(UntilElement(until)) :: res
  }
}

