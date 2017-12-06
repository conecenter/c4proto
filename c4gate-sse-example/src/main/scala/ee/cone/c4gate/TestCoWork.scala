package ee.cone.c4gate

import java.net.URL

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.TestFilterProtocol.Content
import ee.cone.c4proto.Id
import ee.cone.c4ui._
import ee.cone.c4vdom.{TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes

class TestCoWorkApp extends ServerApp
  with `The EnvConfigImpl` with `The VMExecution`
  with KafkaProducerApp with KafkaConsumerApp
  with `The ParallelObserverProvider` with TreeIndexValueMergerFactoryApp
  with UIApp
  with `The TestTagsImpl`
  with `The NoAssembleProfiler`
  with ManagementApp
  with FileRawSnapshotApp
  with `The ModelAccessFactoryImpl`
  with `The SessionAttrAccessFactoryImpl`
  with `The ByLocationHashView`
  with `The TestCoWorkerView`
  with `The TestCoLeaderView`
  with `The ContentDefault`
  with `The TestFilterProtocol`
  with `The ReactAppAssemble`

//@fieldAccess
object TestContentAccess {
  lazy val value: ProdLens[Content,String] = ProdLens.of(_.value)
}
object TestAttrs {
  lazy val contentFlt = SessionAttr(Id(0x0008), classOf[Content], UserLabel en "(Content)")
}

@c4component @listed case class ContentDefault() extends DefaultModelFactory(classOf[Content], Content(_,""))

@c4component @listed case class TestCoWorkerView(locationHash: String = "worker")(
  tags: TestTags,
  sessionAttrAccess: SessionAttrAccessFactory
) extends ByLocationHashView  {
  def view: Context ⇒ ViewRes = local ⇒ {
    for {
      content ← (sessionAttrAccess to TestAttrs.contentFlt)(local).toList
      tags ← tags.input(content to TestContentAccess.value) :: Nil
    } yield tags
  }
}

@c4component @listed case class TestCoLeaderView(locationHash: String = "leader")(
  tags: Tags,
  styles: TagStyles,
  branchOperations: BranchOperations,
  untilPolicy: UntilPolicy,
  fromAlienStates: ByPK[FromAlienState] @c4key
) extends ByLocationHashView with LazyLogging {
  import tags._
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    val fromAliens = for(
      fromAlien ← fromAlienStates.of(local).values;
      url ← Option(new URL(fromAlien.location));
      ref ← Option(url.getRef) if ref != "leader"
    ) yield fromAlien
    val seeds = fromAliens.toList.sortBy(_.sessionKey)
      .map(branchOperations.toSeed)
    divButton("add")(stats)(List(text("caption", "stats"))) ::
      seeds.map(seed(_)(List(styles.width(100), styles.height(100)))(Nil))
  }
  private def stats: Context ⇒ Context = local ⇒ {
    logger.info(WorldStats.make(local))
    local
  }
}
