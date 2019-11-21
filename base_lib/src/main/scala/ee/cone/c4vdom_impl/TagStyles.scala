package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{Color, MutableJsonBuilder, TagStyle, TagStyles}

case object NoTagStyle extends TagStyle {
  def appendStyle(builder: MutableJsonBuilder) = ()
}
abstract class SingleTagStyle() extends TagStyle {
  def key: String
  def valueStr: String
  def appendStyle(builder: MutableJsonBuilder) = builder.append(key).append(valueStr)
}
abstract class StaticTagStyle(val key: String, val valueStr: String)
  extends SingleTagStyle

abstract class DisplayTagStyle(valueStr: String)
  extends StaticTagStyle("display",valueStr)
case object InlineBlockTagStyle extends DisplayTagStyle("inline-block")
case object BlockTagStyle extends DisplayTagStyle("block")
case object FlexTagStyle extends DisplayTagStyle("flex")
case object CellTagStyle extends DisplayTagStyle("table-cell")
case object TableTagStyle extends DisplayTagStyle("table")
abstract class TextAlignTagStyle(valueStr: String)
  extends StaticTagStyle("textAlign",valueStr)
case object LeftAlignTagStyle extends TextAlignTagStyle("left")
case object CenterAlignTagStyle extends TextAlignTagStyle("center")
case object RightAlignTagStyle extends TextAlignTagStyle("right")
abstract class VerticalAlignTagStyle(valueStr: String)
  extends StaticTagStyle("verticalAlign",valueStr)
case object BottomAlignTagStyle extends VerticalAlignTagStyle("bottom")
case object MiddleAlignTagStyle extends VerticalAlignTagStyle("middle")
case object TopAlignTagStyle extends VerticalAlignTagStyle("top")
case object RelativeTagStyle extends StaticTagStyle("position","relative")
case object NoWrapTagStyle extends StaticTagStyle("whiteSpace","nowrap")
case object FlexWrapTagStyle extends StaticTagStyle("flexWrap","wrap")
case object WidthAllTagStyle extends StaticTagStyle("width","100%")
case object HeightAllTagStyle extends StaticTagStyle("height","100%")
case object MarginLeftAuto extends StaticTagStyle("marginLeft","auto")
case object MarginRightAuto extends StaticTagStyle("marginRight","auto")

abstract class PxTagStyle extends SingleTagStyle {
  def value: Int
  def valueStr = s"${value}px"
}
case class MarginTagStyle(value: Int) extends PxTagStyle{ def key = "margin" }
case class PaddingTagStyle(value: Int) extends PxTagStyle{ def key = "padding" }
case class WidthTagStyle(value: Int) extends PxTagStyle{ def key = "width" }
case class MinWidthTagStyle(value: Int) extends PxTagStyle{ def key = "minWidth" }
case class MaxWidthTagStyle(value: Int) extends PxTagStyle{ def key = "maxWidth" }
case class HeightTagStyle(value:Int) extends PxTagStyle{ def key = "height" }
case class MinHeightTagStyle(value: Int) extends PxTagStyle{ def key = "minHeight" }
case class FontSizeTagStyle(value: Int) extends PxTagStyle{ def key = "fontSize" }

case class WidthAllButTagStyle(value: Int) extends SingleTagStyle {
  def key = "width"
  def valueStr = s"calc(100% - ${value}px)"
}

case class ColorTagStyle(color: Color) extends SingleTagStyle {
  def key = "color"
  def valueStr = color.value
}

class TagStylesImpl extends TagStyles {
  def none = NoTagStyle
  def margin = MarginTagStyle
  def marginLeftAuto = MarginLeftAuto
  def marginRightAuto = MarginRightAuto
  def displayInlineBlock = InlineBlockTagStyle
  def displayBlock = BlockTagStyle
  def alignLeft = LeftAlignTagStyle
  def alignRight = RightAlignTagStyle
  def displayCell = CellTagStyle
  def widthAll = WidthAllTagStyle
  def widthAllBut = WidthAllButTagStyle
  def alignCenter = CenterAlignTagStyle
  def minWidth = MinWidthTagStyle
  def maxWidth = MaxWidthTagStyle
  def alignMiddle = MiddleAlignTagStyle
  def displayTable = TableTagStyle
  def displayFlex = FlexTagStyle
  def padding = PaddingTagStyle
  def width = WidthTagStyle
  def height = HeightTagStyle
  def heightAll = HeightAllTagStyle
  def minHeight = MinHeightTagStyle
  def alignBottom = BottomAlignTagStyle
  def alignTop = TopAlignTagStyle
  def relative = RelativeTagStyle
  def color = ColorTagStyle
  def noWrap = NoWrapTagStyle
  def flexWrap = FlexWrapTagStyle
  def fontSize = FontSizeTagStyle
}
