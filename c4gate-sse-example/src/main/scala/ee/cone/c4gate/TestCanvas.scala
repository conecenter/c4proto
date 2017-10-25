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
import ee.cone.c4vdom.{PathContext, PathContextImpl, _}

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

    val canvas = PathContextKey.of(local)
    def canvasSeed(access: Access[String]) =
      cTags.canvas("testCanvas",Nil,List(styles.height(512),styles.widthAll), access)(
        viewRel(0)(canvas)(local)::viewRel(50)(canvas)(local)::Nil
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
  def someInnerView(pathContext: PathContext):Context=>ChildPair[OfCanvas]=_=>{
    pathContext.path("3",List(Rect(0,0,20,20),FillStyle("rgb(0,0,0)")))(Nil)
  }
  def otherInnerView(pathContext: PathContext):Context=>ChildPair[OfCanvas]=_=>{
    pathContext.path("4",Nil)(Nil)
  }
  def viewRel: Int ⇒ PathContext ⇒ Context ⇒ ChildPair[OfCanvas] = offset ⇒ canvas ⇒ local ⇒ {
    val key = "123"+offset
    canvas.path(key, List(Rect(10+offset,20,30,40),GotoClick(key),FillStyle("rgb(255,0,0)"),StrokeStyle("#000000")))(List(
      someInnerView(canvas.add(Translate(50,50)).add(Rotate(10)))(local),
      otherInnerView(canvas)(local)
    ))
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
      new PathContextInject(PathContextImpl[Context](Nil)(childPairFactory,CanvasToJsonImpl)) ::
      super.toInject
}

case object PathContextKey extends SharedComponentKey[PathContext]
class PathContextInject(pathContext: PathContext) extends ToInject {
  def toInject: List[Injectable] = PathContextKey.set(pathContext)
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
    toJson.appendJson(attr, builder)
    builder.end()
  }
}

case object TestCanvasTagsKey extends SharedComponentKey[TestCanvasTags]
class TestCanvasTags(child: ChildPairFactory, utils: TagJsonUtils, toJson: CanvasToJson) extends ToInject {
  def toInject: List[Injectable] = TestCanvasTagsKey.set(this)
  def messageStrBody(o: VDomMessage): String =
    o.body match { case bs: okio.ByteString ⇒ bs.utf8() }
  def canvas(key: VDomKey, attr: List[CanvasAttr], style: List[TagStyle], access: Access[String])(children: List[ChildPair[OfCanvas]]): ChildPair[OfDiv] =
    child[OfDiv](
      key,
      CanvasElement(attr, style, access.initialValue)(
        utils, toJson,
        message ⇒ access.updatingLens.get.set(messageStrBody(message))
      ),
      children
    )
}
