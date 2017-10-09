package ee.cone.c4gate


import java.text.DecimalFormat

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.{Assemble, assemble, fieldAccess}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4gate.TestCanvasProtocol.TestCanvasState
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4ui._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}

class TestCanvasApp extends ServerApp
  with EnvConfigApp with VMExecutionApp
  with KafkaProducerApp with KafkaConsumerApp
  with ParallelObserversApp
  with UIApp
  with PublishingApp
  with TestTagsApp
  //with CanvasApp
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
  override def toInject: List[ToInject] =
    new TestCanvasTags(childPairFactory,tagJsonUtils) :: super.toInject
}

@protocol object TestCanvasProtocol extends Protocol {
  @Id(0x0008) case class TestCanvasState(
    @Id(0x0009) sessionKey: String,
    @Id(0x000A) sizes: String
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

case class TestRectElement(x: Int, y: Int) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    val decimalFormat = new DecimalFormat("#0.##")
    builder.startObject()
    builder.append("commands"); {
      builder.startArray();
      {
        startContext("preparingCtx")(builder);
        {
          builder.startArray()
          builder.append(x,decimalFormat)
          builder.append(y,decimalFormat)
          builder.append(200,decimalFormat)
          builder.append(200,decimalFormat)
          builder.end()
          builder.append("strokeRect")
        };
        endContext(builder)
      }
      builder.end()
    }
    builder.end()
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

case class CanvasElement(styles: List[TagStyle], value: String)(
  utils: TagJsonUtils, val receive: VDomMessage ⇒ Context ⇒ Context
) extends VDomValue with Receiver[Context] {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("Canvas")
    builder.append("ctx").append("ctx")
    builder.append("content").startArray().append("rawMerge").end()
    utils.appendInputAttributes(builder, value, deferSend=false)
    utils.appendStyles(builder, styles)

    val decimalFormat = new DecimalFormat("#0.##")
    //val builder = new JsonBuilderImpl()
    builder.append("width").append(1000,decimalFormat) //map size
    builder.append("height").append(1000,decimalFormat)
    val maxScale = 10
    val zoomSteps = 4096
    val maxZoom = (Math.log(maxScale.toDouble)*zoomSteps).toInt
    builder.append("zoomSteps").append(zoomSteps,decimalFormat)
    builder.append("commandZoom").append(0,decimalFormat)
    builder.append("maxZoom").append(maxZoom,decimalFormat)
    builder.end()
  }
}



import TestCanvasStateAccess._
@fieldAccess object TestCanvasStateAccess {
  lazy val sizes: ProdLens[TestCanvasState,String] = ProdLens.of(_.sizes)
}

case object TestCanvasTagsKey extends SharedComponentKey[TestCanvasTags]
class TestCanvasTags(
  child: ChildPairFactory,
  utils: TagJsonUtils
) extends ToInject {
  def toInject: List[Injectable] = TestCanvasTagsKey.set(this)
  def messageStrBody(o: VDomMessage): String =
    o.body match { case bs: okio.ByteString ⇒ bs.utf8() }
  def canvas(key: VDomKey, attr: List[TagStyle], access: Access[String])(children: List[ChildPair[OfCanvas]]): ChildPair[OfDiv] =
    child[OfDiv](
      key,
      CanvasElement(attr, access.initialValue)(
        utils,
        message ⇒ access.updatingLens.get.set(messageStrBody(message))
      ),
      children
    )
  def rect(key: VDomKey, x: Int, y: Int): ChildPair[OfCanvas] =
    child[OfCanvas](
      key,
      TestRectElement(x,y),
      Nil
    )
}

trait OfCanvas

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
    def canvasSeed(access: Access[String]) =
      cTags.canvas("testCanvas",List(styles.height(512),styles.widthAll), access)(
        List(cTags.rect("1",400,400), cTags.rect("2",400,650))
      )

    val relocate = tags.divButton("relocate")(branchTask.relocate("todo"))(
      List(tags.text("caption", "relocate"))
    )
    val inputs = for {
      canvasTask ← (conductor to canvasTaskProd).toList
      tags ← tTags.input(canvasTask to sizes) :: canvasSeed(canvasTask to sizes) :: Nil
    } yield tags
    relocate :: inputs ::: Nil
  }
}
