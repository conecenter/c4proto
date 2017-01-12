package ee.cone.c4gate

import java.util.UUID

import ee.cone.c4actor.LEvent.{add, delete, update}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.World
import ee.cone.c4gate.TestTodoProtocol.Task
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4vdom.{ChildPair, RootView}

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

class TestTodoRootView(tags: TestTags[World]) extends RootView[World] {
  def view(local: World): (List[ChildPair[_]], Long) = {
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
    (res,until)
  }
}

