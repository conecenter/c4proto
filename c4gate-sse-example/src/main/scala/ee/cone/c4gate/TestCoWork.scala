package ee.cone.c4gate

import ee.cone.c4actor.LEvent.{add, update}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4assemble.Types.{Values, World}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.TestCoWorkProtocol.Content
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4vdom.ChildPair

object TestCoWork extends Main((new TestCoWorkApp).execution.run)

class TestCoWorkApp extends ServerApp
  with EnvConfigApp
  with KafkaProducerApp with KafkaConsumerApp
  with SerialObserversApp
  with VDomSSEApp
{
  lazy val testTags = new TestTags[World](childPairFactory,tagJsonUtils)
  override def protocols: List[Protocol] = TestCoWorkProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestCoWorkAssemble ::
      new FromAlienTaskAssemble("localhost", "/react-app.html") ::
      super.assembles
}

@protocol object TestCoWorkProtocol extends Protocol {
  @Id(0x0001) case class Content(
    @Id(0x0002) sessionKey: String,
    @Id(0x0003) value: String
  )
}

@assemble class TestCoWorkAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      task ← tasks
    ) yield task.branchKey → (
      if(task.locationHash == "leader") TestCoLeaderView()
      else TestCoWorkerView(task.fromAlienState.sessionKey)
    )

}

case class TestCoWorkerView(sessionKey: SrcId) extends View {
  def view: World ⇒ List[ChildPair[_]] = local ⇒ {
    val world = TxKey.of(local).world
    val contents = By.srcId(classOf[Content]).of(world)
    val content = Single(contents.getOrElse(sessionKey,List(Content(sessionKey,""))))
    val tags = TestTagsKey.of(local).get
    val input = tags.input("value", content.value,
      value ⇒ add(update(content.copy(value=value)))
    )
    List(input)
  }
}

case class TestCoLeaderView() extends View {
  def view: World ⇒ List[ChildPair[_]] = local ⇒ {
    val world = TxKey.of(local).world
    val fromAlienTasks = By.srcId(classOf[FromAlienTask]).of(world)
    val mTags = TagsKey.of(local).get
    val branchOperations = BranchOperationsKey.of(local).get
    val workers = fromAlienTasks.values.flatten.filter(_.locationHash != "leader")
    val fromAliens = workers.map(_.fromAlienState).toList.sortBy(_.sessionKey)
    fromAliens.map(branchOperations.toSeed).map(mTags.seed)
  }
}
