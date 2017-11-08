package ee.cone.c4gate

import java.text.DecimalFormat

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, fieldAccess}
import ee.cone.c4gate.TestCanvasProtocol.TestCanvasState
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}
import ee.cone.c4vdom.{PathFactory, PathFactoryImpl, _}

class TestCanvasApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with PublishingApp
  with TestTagsApp
  with CanvasApp
  with UMLClientsApp with NoAssembleProfilerApp
  with ManagementApp
  with FileRawSnapshotApp
{
  override def protocols: List[Protocol] = TestCanvasProtocol :: super.protocols
  override def assembles: List[Assemble] =
    new TestCanvasAssemble ::
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

@assemble class TestCanvasAssemble extends Assemble {
  def joinView(
    key: SrcId,
    tasks: Values[FromAlienTask]
  ): Values[(SrcId,View)] =
    for(
      task ← tasks;
      view ← Option(task.locationHash).collect{
        case "rectangle" ⇒ TestCanvasView(task.branchKey,task.branchTask,task.fromAlienState.sessionKey)
      }
    ) yield WithPK(view)
}

case class TestCanvasView(branchKey: SrcId, branchTask: BranchTask, sessionKey: SrcId) extends View {
  def view: Context ⇒ ViewRes = local ⇒ {
    val tags = TagsKey.of(local)
    val styles = TagStylesKey.of(local)
    val tTags = TestTagsKey.of(local)
    val conductor = ModelAccessFactoryKey.of(local)

    val canvasTasks = ByPK(classOf[TestCanvasState]).of(local)
    val canvasTaskProd: TestCanvasState =
      canvasTasks.getOrElse(sessionKey,TestCanvasState(sessionKey,""))

    val cTags = TestCanvasTagsKey.of(local)

    val canvas = PathFactoryKey.of(local)
    def canvasSeed(access: Access[String]) =
      cTags.canvas("testCanvas",List(styles.height(512),styles.widthAll), access)(
        viewRel(0)(local)::viewRel(50)(local)::Nil
      )

    val relocate = tags.divButton("relocate")(branchTask.relocate("todo"))(
      List(tags.text("caption", "relocate"))
    )
    import ee.cone.c4gate.TestCanvasStateAccess._
    val inputs = for {
      canvasTask ← (conductor to canvasTaskProd).toList
      tags ← tTags.input(canvasTask to sizes) :: canvasSeed(canvasTask to sizes) :: Nil
    } yield tags

    relocate :: inputs ::: Nil
  }
  def viewRel: Int ⇒ Context ⇒ ChildPair[OfCanvas] = offset ⇒ local ⇒ {
    val key = "123"+offset
    val pathFactory = PathFactoryKey.of(local)
    import pathFactory.path
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

@protocol object TestCanvasProtocol extends Protocol {
  @Id(0x0008) case class TestCanvasState(
    @Id(0x0009) sessionKey: String,
    @Id(0x000A) sizes: String
  )
}

@fieldAccess object TestCanvasStateAccess {
  lazy val sizes: ProdLens[TestCanvasProtocol.TestCanvasState,String] = ProdLens.of(_.sizes)
}

/******************************************/

trait CanvasApp extends ToInjectApp {
  def childPairFactory: ChildPairFactory
  def tagJsonUtils: TagJsonUtils

  override def toInject: List[ToInject] =
    new TestCanvasTags(childPairFactory,tagJsonUtils,CanvasToJsonImpl) ::
      new PathFactoryInject(PathFactoryImpl[Context](childPairFactory,CanvasToJsonImpl)) ::
      super.toInject
}

case object PathFactoryKey extends SharedComponentKey[PathFactory]
class PathFactoryInject(pathContext: PathFactory) extends ToInject {
  def toInject: List[Injectable] = PathFactoryKey.set(pathContext)
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

case object TestCanvasTagsKey extends SharedComponentKey[TestCanvasTags]
class TestCanvasTags(child: ChildPairFactory, utils: TagJsonUtils, toJson: CanvasToJson) extends ToInject {
  def toInject: List[Injectable] = TestCanvasTagsKey.set(this)
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