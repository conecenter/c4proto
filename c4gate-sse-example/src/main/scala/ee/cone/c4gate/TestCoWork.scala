package ee.cone.c4gate

import java.net.URL

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, fieldAccess}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.SessionDataProtocol.U_RawSessionData
import ee.cone.c4gate.TestFilterProtocol.B_Content
import ee.cone.c4proto.{Id, Protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.{TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes

class TestCoWorkApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with UIApp
  with PublishingApp
  with TestTagsApp
  with SimpleAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryApp
  with SessionAttrApp
  with SessionAttrAccessFactoryImplApp
  with MortalFactoryApp
  with DefaultModelFactoriesApp
  with ByLocationHashViewsApp
  with MergingSnapshotApp
  with TestCoWorkerViewApp
  with TestCoLeaderViewApp
  with TestTxLogApp
  with SSHDebugApp
{
  override def protocols: List[Protocol] = TestFilterProtocol :: super.protocols
  override def assembles: List[Assemble] =
      new FromAlienTaskAssemble("/react-app.html") ::
      super.assembles
  override def defaultModelFactories: List[DefaultModelFactory[_]] =
    ContentDefault :: super.defaultModelFactories
  def mimeTypes: Map[String, String] = Map(
    "html" → "text/html; charset=UTF-8"
  )
  def publishFromStrings: List[(String, String)] = List(
    "/blank.html" → s"""<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>"""
  )
}

@fieldAccess object TestContentAccessBase {
  lazy val value: ProdLens[B_Content,String] = ProdLens.of(_.value)
}
object TestAttrs {
  lazy val contentFlt = SessionAttr(Id(0x0008), classOf[B_Content], UserLabel en "(Content)")
}

object ContentDefault extends DefaultModelFactory(classOf[B_Content], B_Content(_,""))

trait TestCoWorkerViewApp extends ByLocationHashViewsApp {
  def testTags: TestTags[Context]
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  private lazy val testCoWorkerView = TestCoWorkerView()(testTags,sessionAttrAccessFactory)
  override def byLocationHashViews: List[ByLocationHashView] =
    testCoWorkerView :: super.byLocationHashViews
}

case class TestCoWorkerView(locationHash: String = "worker")(
  tags: TestTags[Context],
  sessionAttrAccess: SessionAttrAccessFactory
) extends ByLocationHashView  {
  def view: Context ⇒ ViewRes = local ⇒ {
    for {
      content ← (sessionAttrAccess to TestAttrs.contentFlt)(local).toList
      tags ← tags.input(content to TestContentAccess.value) :: Nil
    } yield tags
  }
}

trait TestCoLeaderViewApp extends ByLocationHashViewsApp {
  def tags: Tags
  def tagStyles: TagStyles
  def branchOperations: BranchOperations
  def untilPolicy: UntilPolicy
  private lazy val testCoLeaderView = TestCoLeaderView()(tags,tagStyles,branchOperations,untilPolicy)
  override def byLocationHashViews: List[ByLocationHashView] =
    testCoLeaderView :: super.byLocationHashViews
}

case class TestCoLeaderView(locationHash: String = "leader")(
  tags: Tags,
  styles: TagStyles,
  branchOperations: BranchOperations,
  untilPolicy: UntilPolicy
) extends ByLocationHashView with LazyLogging {
  import tags._
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    val fromAlienStates = ByPK(classOf[FromAlienState]).of(local)
    val fromAliens = for(
      fromAlien ← fromAlienStates.values;
      url ← Option(new URL(fromAlien.location));
      ref ← Option(url.getRef) if ref != "leader"
    ) yield fromAlien
    val seeds = fromAliens.toList.sortBy(_.sessionKey)
      .map(branchOperations.toSeed)
    divButton("add")(stats)(List(text("caption", "stats"))) ::
      seeds.map(seed(_)(List(styles.width(100), styles.height(100)), "/blank.html")(Nil))
  }
  private def stats: Context ⇒ Context = local ⇒ {
    logger.info(WorldStats.make(local))
    local
  }
}
