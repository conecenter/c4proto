package ee.cone.c4gate

import java.awt.geom.AffineTransform

trait PathAttr
/* NoGen */ trait AbstractCanvasEventHandler extends PathAttr
////
/* NoGen */trait BaseStyleCommand extends PathAttr
/* NoGen */trait BasePathStyle extends BaseStyleCommand
/* NoGen */trait BaseTextStyle extends BaseStyleCommand
/* NoGen */trait BaseFillStyle extends BaseStyleCommand
/* NoGen */trait BaseStrokeStyle extends BaseStyleCommand
/* NoGen */trait CanvasAnimation extends BaseStyleCommand

case object DraggingCommand extends BaseStyleCommand
//case object FadeStyle extends BaseStyleCommand
case class CustomPathHandler(serverEventType: String, modelKey: String, ignored: String) extends AbstractCanvasEventHandler
case class CustomProductPathHandler(serverEventType: String, product: Product, extra: Map[String, Object] = Map()) extends AbstractCanvasEventHandler
case class UIDragStartEventCanvasHandler(serverEventType: String, modelKey: String) extends AbstractCanvasEventHandler
case class UIDragEndEventCanvasHandler(serverEventType: String, modelKey: String)(val affineTransform: Option[AffineTransform]=None) extends AbstractCanvasEventHandler
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