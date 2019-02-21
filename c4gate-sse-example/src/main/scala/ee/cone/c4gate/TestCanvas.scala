package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, fieldAccess}
import ee.cone.c4gate.TestCanvasProtocol.TestCanvasState
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}
import ee.cone.c4vdom.{PathFactory, PathFactoryImpl, _}

class TestCanvasApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp with TreeIndexValueMergerFactoryApp
  with UIApp
  with PublishingApp
  with TestTagsApp
  with CanvasApp
  with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
  with PublicViewAssembleApp
  with ModelAccessFactoryApp
  with SessionAttrApp
  with MortalFactoryApp
  with TestCanvasViewApp
{
  override def assembles: List[Assemble] =
      new FromAlienTaskAssemble("/react-app.html") ::
      super.assembles
  def mimeTypes: Map[String, String] = Map(
    "svg" → "image/svg+xml"
  )
  def publishFromStrings: List[(String, String)] = List(
    "/test.svg" → s"""<?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <svg xmlns="http://www.w3.org/2000/svg" width="500" height="500">
      <circle cx="250" cy="250" r="210" fill="#fff" stroke="#000" stroke-width="8"/>
      </svg>"""
  )
}

trait TestCanvasViewApp extends ByLocationHashViewsApp {
  def testTags: TestTags[Context]
  def tags: Tags
  def tagStyles: TagStyles
  def testCanvasTags: TestCanvasTags
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def untilPolicy: UntilPolicy
  def pathFactory: PathFactory
  private lazy val testCanvasView = TestCanvasView()(
    testTags,
    tags,
    tagStyles,
    testCanvasTags,
    sessionAttrAccessFactory,
    untilPolicy,
    pathFactory
  )
  override def byLocationHashViews: List[ByLocationHashView] =
    testCanvasView :: super.byLocationHashViews
}


case class TestCanvasView(locationHash: String = "rectangle")(
  tTags: TestTags[Context],
  tags: Tags,
  styles: TagStyles,
  cTags: TestCanvasTags,
  sessionAttrAccessFactory: SessionAttrAccessFactory,
  untilPolicy: UntilPolicy,
  pathFactory: PathFactory
) extends ByLocationHashView {
  import pathFactory.path
  import TestCanvasStateAccess.sizes
  def view: Context ⇒ ViewRes = untilPolicy.wrap{ local ⇒
    def canvasSeed(access: Access[String]) =
      cTags.canvas("testCanvas",List(styles.height(512),styles.widthAll), access)(
        viewRel(0)(local)::viewRel(50)(local)::Nil
      )
    val branchTask = ByPK(classOf[BranchTask]).of(local)(CurrentBranchKey.of(local))
    val relocate = tags.divButton("relocate")(branchTask.relocate("todo"))(
      List(tags.text("caption", "relocate"))
    )
    val state: Option[Access[TestCanvasState]] =
      sessionAttrAccessFactory.to(TestCanvasStateAccess.state)(local)
    val inputs = for {
      canvasTask ← state.toList
      tags ← tTags.input(canvasTask to sizes) :: canvasSeed(canvasTask to sizes) :: Nil
    } yield tags
    relocate :: inputs ::: Nil
  }
  def viewRel: Int ⇒ Context ⇒ ChildPair[OfCanvas] = offset ⇒ local ⇒ {
    val key = "123"+offset
    path(key,
      Rect(10+offset,20,30,40),
      GotoClick(key),
      FillStyle("rgb(255,0,0)"), StrokeStyle("#000000"),
      path("3",
        Translate(0,50), Rotate(0.1),
        path("3",Rect(0,0,20,20),FillStyle("rgb(0,0,0)"))
      ),
      path("4")
    )
  }
}

case class GotoClick(vDomKey: VDomKey) extends ClickPathHandler[Context] {
  def handleClick: (Context) => Context = (l:Context)=>{println("clicked"+vDomKey);l}
}

/******************************************/

@protocol(TestCat) object TestCanvasProtocol   {
  @Id(0x0008) case class TestCanvasState(
    @Id(0x0009) srcId: String,
    @Id(0x000A) sizes: String
  )
}

@fieldAccess object TestCanvasStateAccess {
  lazy val sizes: ProdLens[TestCanvasProtocol.TestCanvasState,String] = ProdLens.of(_.sizes)
  lazy val state =
    SessionAttr(Id(0x0009), classOf[TestCanvasState], UserLabel en "(TestCanvasState)")
}

object TestCanvasStateDefault extends DefaultModelFactory(classOf[TestCanvasState],TestCanvasState(_,""))

trait CanvasApp extends ProtocolsApp with DefaultModelFactoriesApp {
  def childPairFactory: ChildPairFactory
  def tagJsonUtils: TagJsonUtils

  lazy val testCanvasTags: TestCanvasTags = new TestCanvasTagsImpl(childPairFactory,tagJsonUtils,CanvasToJsonImpl)
  lazy val pathFactory: PathFactory = PathFactoryImpl[Context](childPairFactory,CanvasToJsonImpl)
  
  override def protocols: List[Protocol] =
    TestCanvasProtocol :: super.protocols
  override def defaultModelFactories: List[DefaultModelFactory[_]] =
    TestCanvasStateDefault :: super.defaultModelFactories
}

case class CanvasElement(attr: List[CanvasAttr], styles: List[TagStyle], value: String)(
  utils: TagJsonUtils,
  toJson: CanvasToJson,
  val receive: VDomMessage ⇒ Context ⇒ Context
) extends VDomValue with Receiver[Context] {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    utils.appendInputAttributes(builder, value, deferSend=false)
    utils.appendStyles(builder, styles)
    toJson.appendCanvasJson(attr, builder)
    builder.end()
  }
}

trait TestCanvasTags {
  def canvas(key: VDomKey, style: List[TagStyle], access: Access[String])(children: List[ChildPair[OfCanvas]]): ChildPair[OfDiv]
}

class TestCanvasTagsImpl(child: ChildPairFactory, utils: TagJsonUtils, toJson: CanvasToJson) extends TestCanvasTags {
  def messageStrBody(o: VDomMessage): String =
    o.body match { case bs: okio.ByteString ⇒ bs.utf8() }
  def canvas(key: VDomKey, style: List[TagStyle], access: Access[String])(children: List[ChildPair[OfCanvas]]): ChildPair[OfDiv] =
    child[OfDiv](
      key,
      CanvasElement(children.collect{ case a: CanvasAttr ⇒ a }, style, access.initialValue)(
        utils, toJson,
        message ⇒ access.updatingLens.get.set(messageStrBody(message))
      ),
      children.filterNot(_.isInstanceOf[CanvasAttr])
    )
}

/*
object T {
  trait Ch[-C]
  trait OfA
  trait OfB extends OfA
  def a(l: List[Ch[OfA]]) = ???
  def b(l: List[Ch[OfB]]) = ???

  def chOfA: Ch[OfA] = ???
  def chOfB: Ch[OfB] = ???
  a(List(chOfA))
  def chM: Seq[Ch[OfB]] = List(chOfA,chOfB)
  b(List(chOfA,chOfB))

}*/