
package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

trait PathFactory {
  def path(key: VDomKey, children: ChildPair[OfPath]*): ChildPair[OfPathParent]
  def path(key: VDomKey, children: List[ChildPair[OfPath]]): ChildPair[OfPathParent]
}

trait OfPathParent
trait OfPath extends OfPathParent
trait OfCanvas extends OfPathParent

trait PathAttr extends ChildPair[OfPath] { def key = throw new Exception }
trait CanvasAttr extends ChildPair[OfCanvas] { def key = throw new Exception }

trait CanvasToJson {
  def appendCanvasJson(attr: List[CanvasAttr], builder: MutableJsonBuilder): Unit
  def appendPathJson(attrs:List[PathAttr], builder: MutableJsonBuilder): Unit
}

/////////////////////////////////

sealed trait Transform extends PathAttr
case class Scale(value:BigDecimal) extends Transform
case class Rotate(value:BigDecimal) extends Transform
case class Translate(x:BigDecimal,y:BigDecimal) extends Transform

sealed trait Shape extends PathAttr
sealed trait PathShape extends Shape
sealed trait NonPathShape extends Shape
final case class Rect(x:Double, y:Double, width:Double, height:Double) extends PathShape
final case class Line(x:BigDecimal, y:BigDecimal, toX:BigDecimal, toY:BigDecimal) extends PathShape
final case class BezierCurveTo(sdx: BigDecimal, sdy: BigDecimal, edx:BigDecimal, edy:BigDecimal, endPointX:BigDecimal, endPointY:BigDecimal) extends PathShape
final case class Ellipse(x: BigDecimal, y: BigDecimal, rx: BigDecimal, ry: BigDecimal, rotate: BigDecimal,
  startAngle: BigDecimal, endAngle: BigDecimal, counterclockwise: Boolean
) extends PathShape
final case class Image(url: String, width: Int, height: Int, canvasWidth: BigDecimal, canvasHeight: BigDecimal) extends NonPathShape
final case class Text(styles: List[BaseStyleCommand],text:String,x:BigDecimal,y:BigDecimal) extends NonPathShape

abstract class ClickPathHandler[Context] extends AbstractCanvasEventHandler{
  def handleClick: Context => Context
}

/////////////////////////////////

trait AbstractCanvasEventHandler extends PathAttr
////
trait BaseStyleCommand extends PathAttr
trait BasePathStyle extends BaseStyleCommand
trait BaseTextStyle extends BaseStyleCommand
trait BaseFillStyle extends BaseStyleCommand
trait BaseStrokeStyle extends BaseStyleCommand
trait CanvasAnimation extends BaseStyleCommand

case object DraggingCommand extends BaseStyleCommand
//case object FadeStyle extends BaseStyleCommand
case class CustomPathHandler(serverEventType: String, modelKey: String, ignored: String) extends AbstractCanvasEventHandler
case class CustomProductPathHandler(serverEventType: String, product: Product, extra: Map[String, Product] = Map()) extends AbstractCanvasEventHandler
case class UIDragStartEventCanvasHandler(serverEventType: String, modelKey: String) extends AbstractCanvasEventHandler
//case class UIDragEndEventCanvasHandler(serverEventType: String, modelKey: String)(val affineTransform: Option[A f f i n e Tr a n s fo rm]=None) extends AbstractCanvasEventHandler
case class LinearGradient( //for path
  x0: BigDecimal, y0: BigDecimal, x1: BigDecimal, y1: BigDecimal,
  stops: Seq[(BigDecimal,String)]
) extends BaseFillStyle with BaseStyleCommand
case class PatternLine(
  fromX: BigDecimal, fromY: BigDecimal, toX:BigDecimal, toY:BigDecimal
)
case class Pattern(
  width: Int, height: Int, filler: Seq[PatternLine],
  color: String, backgroundColor: String, size: BigDecimal
) extends BaseFillStyle with BaseStyleCommand
case class DominantBaselineCentralStyle(central: Boolean) extends BaseTextStyle
case class FillStyle( color: String) extends BaseFillStyle with BasePathStyle with BaseTextStyle
case class FontStyle( font: String, fontSize: BigDecimal = 1, fontWeight: String = "normal") extends BaseTextStyle
case class CursorStyle( cursor: String) extends BaseTextStyle with BasePathStyle
case class LineCapStyle(capType: String) extends BasePathStyle
case class LineJoinStyle(joinType: String) extends BasePathStyle
case class SetLineDash( dash: String) extends BaseStyleCommand
case class ZoomInAnimationStyle(uniqueId: String, x: BigDecimal, y: BigDecimal, currentProgress: BigDecimal, totalTime: BigDecimal) extends CanvasAnimation
case class ZoomOutAnimationStyle(uniqueId: String, x: BigDecimal, y: BigDecimal, currentProgress: BigDecimal, totalTime: BigDecimal) extends CanvasAnimation
case class StrokeStyle( stroke: String) extends BaseStrokeStyle with BasePathStyle with BaseTextStyle
case class StrokeWidthStyle( width: BigDecimal) extends BasePathStyle with BaseTextStyle
case class DragOverStrokeWidthStyle( width: BigDecimal) extends BasePathStyle with BaseTextStyle
case class DragOverStrokeStyle( color: String) extends BaseStrokeStyle with BasePathStyle with BaseTextStyle
case class DragOverFillStyle( color: String) extends BaseFillStyle with BasePathStyle with BaseTextStyle
object TextAnchorStyleTypes{ //todo different TextAnchorStyle-s
  val left = "left"
  val right = "right"
  val center = "center"
}
case class TextAnchorStyle( value: String= TextAnchorStyleTypes.right) extends BaseTextStyle
case class TextBaseline(lineType: String) extends BaseStyleCommand

case object TextNoWrap extends BaseTextStyle
case object TextVerticalAlign extends BaseTextStyle
