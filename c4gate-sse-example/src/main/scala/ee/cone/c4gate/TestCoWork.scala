package ee.cone.c4gate

import java.net.URL

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
  @Id(0x0005) case class Content(
    @Id(0x0006) sessionKey: String,
    @Id(0x0007) value: String
  )
}

@assemble class TestCoWorkAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      task ← tasks;
      view ← Option(task.locationHash).collect{
        case "leader" ⇒ TestCoLeaderView(task.branchKey)
        case "worker" ⇒ TestCoWorkerView(task.branchKey,task.fromAlienState.sessionKey)
      }
    ) yield task.branchKey → view
}

case class TestCoWorkerView(branchKey: SrcId, sessionKey: SrcId) extends View {
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

case class TestCoLeaderView(branchKey: SrcId) extends View {
  def view: World ⇒ List[ChildPair[_]] = local ⇒ UntilPolicyKey.of(local){ ()⇒
    val world = TxKey.of(local).world
    val fromAlienStates = By.srcId(classOf[FromAlienState]).of(world)
    val mTags = TagsKey.of(local).get
    val tags = TestTagsKey.of(local).get
    val branchOperations = BranchOperationsKey.of(local).get
    val fromAliens = for(
      fromAlien ← fromAlienStates.values.flatten;
      url ← Option(new URL(fromAlien.location));
      ref ← Option(url.getRef) if ref != "leader"
    ) yield fromAlien
    tags.button("add", "stats",{ local ⇒
      val world = TxKey.of(local).world
      println(WorldStats.make(world))
      local
    }) ::
    fromAliens.toList.sortBy(_.sessionKey).map(branchOperations.toSeed).map(mTags.seed)
  }
}
