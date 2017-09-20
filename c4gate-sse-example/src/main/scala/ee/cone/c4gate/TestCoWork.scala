package ee.cone.c4gate

import java.net.URL

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, assemble, fieldAccess}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.TestFilterProtocol.Content
import ee.cone.c4proto.Protocol
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.ViewRes

class TestCoWorkApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with TestTagsApp
  with UMLClientsApp with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
{
  override def protocols: List[Protocol] = TestFilterProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestCoWorkAssemble ::
      new FromAlienTaskAssemble("/react-app.html") ::
      super.assembles
}

@assemble class TestCoWorkAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[FromAlienTask]
  ): Values[(SrcId, View)] =
    for(
      task ← tasks;
      view ← Option(task.locationHash).collect {
        case "leader" ⇒ TestCoLeaderView(task.branchKey)
        case "worker" ⇒ TestCoWorkerView(
          task.branchKey,
          task.fromAlienState.sessionKey
        )
      }
    ) yield WithPK(view)
}


@fieldAccess object TestContentAccess {
  lazy val value: ProdLens[Content,String] = ProdLens.of(_.value)
}

case class TestCoWorkerView(branchKey: SrcId, sessionKey: SrcId) extends View {
  def view: Context ⇒ ViewRes = local ⇒ {
    val tags = TestTagsKey.of(local)
    val conductor = ModelAccessFactoryKey.of(local)

    val contents = ByPK(classOf[Content]).of(local)
    val contentProd = contents.getOrElse(sessionKey, Content(sessionKey, ""))
    for {
      content ← (conductor to contentProd).toList
      tags ← tags.input(content to TestContentAccess.value) :: Nil
    } yield tags
  }
}

case class TestCoLeaderView(branchKey: SrcId) extends View {
  def view: Context ⇒ ViewRes = local ⇒ UntilPolicyKey.of(local) { () ⇒
    val fromAlienStates = ByPK(classOf[FromAlienState]).of(local)
    val tags = TagsKey.of(local)
    val styles = TagStylesKey.of(local)
    import tags._
    val branchOperations = BranchOperationsKey.of(local)
    val fromAliens = for(
      fromAlien ← fromAlienStates.values;
      url ← Option(new URL(fromAlien.location));
      ref ← Option(url.getRef) if ref != "leader"
    ) yield fromAlien
    val seeds = fromAliens.toList.sortBy(_.sessionKey)
      .map(branchOperations.toSeed)
    divButton("add")(printStats)(List(text("caption", "stats"))) ::
      seeds.map(seed(_)(List(styles.width(100), styles.height(100)))(Nil))
  }
  private def printStats: Context ⇒ Context = local ⇒ {
    println(WorldStats.make(local))
    local
  }
}
