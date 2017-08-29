package ee.cone.c4gate

import ee.cone.c4ui.CanvasContent
import java.text.DecimalFormat

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, assemble}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4gate.TestCanvasProtocol.TestCanvasState
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom.MutableJsonBuilder
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom_impl.JsonBuilderImpl

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

case object CanvasTaskX extends TextInputLens[TestCanvasState](_.x,v⇒_.copy
(x=v))
case object CanvasTaskY extends TextInputLens[TestCanvasState](_.y,v⇒_.copy(y=v))

@protocol object TestCanvasProtocol extends Protocol {
  @Id(0x0008) case class TestCanvasState(
    @Id(0x0009) sessionKey: String,
    @Id(0x000A) x: String,
    @Id(0x000B) y: String
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

  def joinCanvas(
    key: SrcId,
    branchTasks: Values[BranchTask]
  ): Values[(SrcId,CanvasHandler)] =
    for (
      branchTask ← branchTasks;
      state ← Option(branchTask.product).collect { case s: TestCanvasState ⇒ s }
    ) yield branchTask.branchKey → TestCanvasHandler(branchTask.branchKey, state.sessionKey)


}

case class TestCanvasHandler(branchKey: SrcId, sessionKey: SrcId) extends CanvasHandler {
  def messageHandler: BranchMessage ⇒ Context ⇒ Context = ???
  def view: Context ⇒ CanvasContent = local ⇒ {
    val decimalFormat = new DecimalFormat("#0.##")
    val builder = new JsonBuilderImpl()
    builder.startObject()
    CanvasSizesKey.of(local).foreach(s ⇒ builder.append("sizes").append(s.sizes))
    builder.append("width").append(1000,decimalFormat) //map size
    builder.append("height").append(1000,decimalFormat)
    val maxScale = 10
    val zoomSteps = 4096
    val maxZoom = (Math.log(maxScale.toDouble)*zoomSteps).toInt
    builder.append("zoomSteps").append(zoomSteps,decimalFormat)
    builder.append("commandZoom").append(0,decimalFormat)
    builder.append("maxZoom").append(maxZoom,decimalFormat)
    builder.append("commands"); {
      builder.startArray();
      {
        startContext("preparingCtx")(builder);
        {
          builder.startArray()
          builder.append(400,decimalFormat)
          builder.append(400,decimalFormat)
          builder.append(200,decimalFormat)
          builder.append(200,decimalFormat)
          builder.end()
          builder.append("strokeRect")
        };
        {
          //???
        }

        endContext(builder)
      }
      builder.end()
    }
    builder.end()
    //
    val res =builder.result.toString
    CanvasContentImpl(res,System.currentTimeMillis+1000)
  }
  private def startContext(name: String)(builder: MutableJsonBuilder) = {
    builder.startArray()
    builder.append(name)
    builder.startArray()
  }
  private def endContext(builder: MutableJsonBuilder) = {
    builder.end()
    builder.end()
    builder.append("inContext")
  }
}



case class TestCanvasView(branchKey: SrcId, branchTask: BranchTask, sessionKey: SrcId) extends View {
  def view: Context ⇒ ViewRes = local ⇒ {
    val canvasTasks = ByPK(classOf[TestCanvasState]).of(local)
    val branchOperations = BranchOperationsKey.of(local)
    val tags = TagsKey.of(local)
    val styles = TagStylesKey.of(local)
    val tTags = TestTagsKey.of(local)
    val canvasTask: TestCanvasState =
      canvasTasks.getOrElse(sessionKey,TestCanvasState(sessionKey,"",""))
    //
    val inputX = tTags.toInput("x", CanvasTaskX)
    val inputY = tTags.toInput("y", CanvasTaskY)
    val canvasSeed = (t:TestCanvasState) ⇒
      tags.seed(branchOperations.toSeed(t))(List(styles.height(512),styles.widthAll))(Nil)//view size
    val relocate = tags.divButton("relocate")(branchTask.relocate("todo"))(
      List(tags.text("caption", "relocate"))
    )
    List(relocate, inputX(canvasTask), inputY(canvasTask), canvasSeed(canvasTask))
  }
}

case class CanvasContentImpl(value: String, until: Long) extends CanvasContent

