package ee.cone.c4gate

import java.net.URL

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4assemble.fieldAccess
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4gate.TestFilterProtocol.B_Content
import ee.cone.c4proto.{Id, c4}
import ee.cone.c4ui._
import ee.cone.c4vdom.{TagStyles, Tags}
import ee.cone.c4vdom.Types.ViewRes





@c4("TestCoWorkApp") class TestCoWorkPublishFromStringsProvider extends PublishFromStringsProvider {
  def get: List[(String, String)] = List(
    "/blank.html" -> s"""<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>"""
  )
}

@fieldAccess object TestContentAccessBase {
  lazy val value: ProdLens[B_Content,String] = ProdLens.of(_.value)
}
object TestAttrs {
  lazy val contentFlt = SessionAttr(Id(0x0008), classOf[B_Content], UserLabel en "(Content)")
}

@c4("TestCoWorkApp") class ContentDefault extends DefaultModelFactory(classOf[B_Content], B_Content(_,""))

/*
trait TestCoWorkerViewApp extends ByLocationHashViewsApp {
  def testTags: TestTags[Context]
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  private lazy val testCoWorkerView = TestCoWorkerView()(testTags,sessionAttrAccessFactory)
  override def byLocationHashViews: List[ByLocationHashView] =
    testCoWorkerView :: super.byLocationHashViews
}*/

@c4("TestCoWorkApp") case class TestCoWorkerView(locationHash: String = "worker")(
  tags: TestTags[Context],
  sessionAttrAccess: SessionAttrAccessFactory
) extends ByLocationHashView  {
  def view: Context => ViewRes = local => {
    for {
      content <- (sessionAttrAccess to TestAttrs.contentFlt)(local).toList
      tags <- tags.input(content to TestContentAccess.value) :: Nil
    } yield tags
  }
}

/*
trait TestCoLeaderViewApp extends ByLocationHashViewsApp {
  def tags: Tags
  def tagStyles: TagStyles
  def branchOperations: BranchOperations
  def untilPolicy: UntilPolicy
  private lazy val testCoLeaderView = TestCoLeaderView()(tags,tagStyles,branchOperations,untilPolicy)
  override def byLocationHashViews: List[ByLocationHashView] =
    testCoLeaderView :: super.byLocationHashViews
}*/

@c4("TestCoWorkApp") case class TestCoLeaderView(locationHash: String = "leader")(
  tags: Tags,
  styles: TagStyles,
  branchOperations: BranchOperations,
  untilPolicy: UntilPolicy
) extends ByLocationHashView with LazyLogging {
  import tags._
  def view: Context => ViewRes = untilPolicy.wrap{ local =>
    val fromAlienStates = ByPK(classOf[U_FromAlienState]).of(local)
    val fromAliens = for(
      fromAlien <- fromAlienStates.values;
      url <- Option(new URL(fromAlien.location));
      ref <- Option(url.getRef) if ref != "leader"
    ) yield fromAlien
    val seeds = fromAliens.toList.sortBy(_.sessionKey)
      .map(branchOperations.toSeed)
    divButton("add")(stats)(List(text("caption", "stats"))) ::
      seeds.map(seed(_)(List(styles.width(100), styles.height(100)), "/blank.html")(Nil))
  }
  private def stats: Context => Context = local => {
    // logger.info(WorldStats.make(local))
    local
  }
}
