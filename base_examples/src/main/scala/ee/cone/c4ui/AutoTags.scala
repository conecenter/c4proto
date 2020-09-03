package ee.cone.c4ui

import ee.cone.c4actor.MetaAttr
import ee.cone.c4di._
import ee.cone.c4vdom.{ChildPair, ChildPairFactory, MutableJsonBuilder, ToJson, VDomValue, VDomFactory}

trait AAABase

class c4tags(a: String*) extends annotation.Annotation

trait VDomItem extends Product
trait LReceiver

trait ImagePublicPath
trait Seed
object SVGPublicPath {
  def empty: ImagePublicPath = ???
}
trait FieldValue
trait CanEl

trait GeneralJsonAdapter
trait JsonAdapter[T] extends GeneralJsonAdapter {
  def appendJson(key: String, value: T, builder: MutableJsonBuilder): Unit
  def appendJson(value: T, builder: MutableJsonBuilder): Unit
}

// union-types emulated by contra-variance
object AutoTagTest {
  type VDom[C] = ChildPair[C]
  type MetaAttrList = List[MetaAttr]
}
import AutoTagTest._

// ========================== //

trait FlexItemData extends Product
case class FlexItem(key: String, attr:FlexItemData, children:List[FlexItem]) extends VDomItem
trait InlineItem extends VDomItem

case class FColData(layoutParams: Option[LayoutParams], onClick: Option[LReceiver]) extends FlexItemData

trait Sticky
case object NonSticky extends Sticky
case object OneWaySticky extends Sticky //hides on scroll down, shows on scroll up, like menu
case object FullSticky extends Sticky //never hides full width row, like error

trait LayoutParams {
  def widthBasis: Option[BigDecimal]
  def widthMax: Option[BigDecimal]
  def parentWidth: Boolean
  def align: Unit
  def sticky: Sticky
}

object DefaultLayout extends LayoutParams {
  def widthBasis: Option[BigDecimal] = None
  def widthMax: Option[BigDecimal] = None
  def parentWidth: Boolean = false
  def align: Unit = ???  // left
  def sticky: Sticky = NonSticky
}
object DefaultPopupLayout extends LayoutParams {
  def widthBasis: Option[BigDecimal] = None
  def widthMax: Option[BigDecimal] = None // + limit % of ViewPort in css class (~90%)
  def parentWidth: Boolean = false
  def align: Unit = ???  // right in relation of inducing element
  def sticky: Sticky = NonSticky
}

object NoReceiver extends LReceiver {
  //def receive: NoReceiver.Handler = ??? // never
}

trait Color // text + background
trait PaletteColor extends Color // from interface palette, fixed pairs
trait CustomColor extends Color // background by user data (not code), text by contrast formula

trait GroupBoxStyle // only 2 for this moment
object DefaultGroupBoxStyle extends GroupBoxStyle
object StandOutGroupBoxStyle extends GroupBoxStyle

trait OfFlex extends VFlexCol with VFlexRow with VRow with VMenu with VFilterExtra1 with VFilterExtra2 with VFlexCell with VIFrame with VCanvas with VAccordion
trait OfFieldFrame extends OfFlex with VField
trait OfRoot extends OfFlex with VHeader
trait InLines extends VText with VLabel with VImage with VInput with VButton with VChip
trait OfTablePart extends VTr
trait OfTr extends VTh with VTd

trait VElement
trait VRoot extends VElement
trait VFlexCol extends VElement
trait VFlexRow extends VElement
sealed trait VRow extends VElement
trait VMenu extends VElement
sealed trait VFilterExtra1 extends VElement
sealed trait VFilterExtra2 extends VElement
trait VTable extends VElement
trait VTr extends VElement
trait VTh extends VElement
trait VTd extends VElement
trait VFlexCell extends VElement
trait VText extends VElement
trait VLabel extends VElement
trait VImage extends VElement
sealed trait VInput extends VElement
trait VButton extends VElement
trait VChip extends VElement
trait VField extends VElement
trait VHeader extends VElement
trait VIFrame extends VElement
trait VCanvas extends VElement
trait VAccordion extends VElement

trait VTitle extends VElement
trait VAccordionItem extends VElement


@c4tags("AAA") trait AutoTags {
  // onclick = cursor hand + mouse over
  // no font size <- css font size + special mode "zoom all to fit"
  def flexCol(key: String, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(children: VDom[OfFlex]*): VDom[VFlexCol]
  def flexRow(key: String, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(children: VDom[OfFlex]*): VDom[VFlexRow]
  def table(key: String, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver, stickyColumns: Int = 0)(headers: VDom[OfTablePart]*)(rows: VDom[OfTablePart]*): VDom[VTable] // headers allways sticky if rows > 1
  def tr(key: String, onClick: LReceiver = NoReceiver)(children: VDom[OfTr]*): VDom[VTr]
  def td(key: String, colSpan: Int = 1, rowSpan: Int = 1, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(children: VDom[OfFieldFrame]*): VDom[VTd]

  // def setCurrent[T<:VElement](current: Boolean)(el: VDom[T]): VDom[T]

  def flexCell(key: String, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(children: VDom[InLines]*): VDom[VFlexCell]
  // def flexCell(layoutParams: LayoutParams = DefaultLayout)(child: VDom[InLines]): VDom[VFlexCell] // key from child
  def text(key: String, text: String, onClick: LReceiver): VDom[VText]
  def label(key: String, text: String, onClick: LReceiver): VDom[VLabel]
  def image(key: String, path: ImagePublicPath, onClick: LReceiver = NoReceiver): VDom[VImage] // IT todo: + rotate in ImagePublicPath
  def button(key: String, color: PaletteColor, onClick: LReceiver = NoReceiver)(children: VDom[InLines]*): VDom[VButton] // NoReceiver == css class for disabled button
  def chip(key: String, color: Color, onClick: LReceiver = NoReceiver)(children: VDom[InLines]*): VDom[VChip]

  def title(key: String = "single", onClick: LReceiver = NoReceiver)(children: VDom[InLines]*): VDom[VTitle] // no key <- allways single child
  def noTitle(key: String = "single"): VDom[VTitle] // implimentation: val noTitle: VDom[VTitle] = title()()

  def root(key: String = "single")(children: VDom[OfRoot]*): VDom[VRoot]
  def rootHeader(key: String, onClick: LReceiver = NoReceiver)(children: VDom[OfFlex]*): VDom[VHeader]
  def iframe(key: String, layoutParams: LayoutParams = DefaultLayout, seed: Seed): VDom[VIFrame]

  //def virtualKeyboard(???)

  def groupBox(key: String, title: VDom[VTitle] = noTitle(), groupBoxStyle: GroupBoxStyle = DefaultGroupBoxStyle, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(children: VDom[OfFlex]*): VDom[VFlexCol]
  // SK todo popup: own subtree, like seeds, stack of popup case classes, auto close by focus
  def tabsHolder(key: String, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(children: VDom[OfFlex]*): VDom[VFlexRow] // high level tabs?
  def canvas(key: String, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(children: VDom[CanEl]*): VDom[VCanvas] // sk todo: ???

  def filter(key: String = "single")(filters: VDom[OfFlex]*)(selectedRowsButtons: VDom[InLines]*)(allRowsButtons: VDom[InLines]*): VDom[VRow] // or VDom[VFlexRow] but without wrap?

  def menu(key: String, layoutParams: LayoutParams = DefaultLayout, onClick: LReceiver = NoReceiver)(menuItems: VDom[OfFlex]*): VDom[VMenu]
  def menuItem(key: String, path: ImagePublicPath = SVGPublicPath.empty, onClick: LReceiver = NoReceiver)(children: VDom[InLines]*): VDom[VFlexCell] // popup side?

  def accordion(key: String, columns: Int = 2)(items: VDom[VAccordionItem]*): VDom[VAccordion]
  def accordionItem(key: String, opened: Boolean, mainTitle: VDom[VTitle], rightTitle: VDom[VTitle] = noTitle(), columnsWide: Int = 1)(children: VDom[OfFlex]*): VDom[VAccordionItem] // callback? or children only when open?

  def fieldFrame(
    key: String, title: VDom[VTitle], // title mandatory, vertical space for title is always reserved as 1 line
    layoutParams: LayoutParams = DefaultLayout, events: LReceiver = NoReceiver
  )(inputLineChildren: VDom[OfFieldFrame]*): VDom[VFlexCol] // onclick + focus???

  def field( // force no use VK on iPad by route
    key: String,
    value: FieldValue, valueMetas: MetaAttrList = Nil,
    layoutParams: LayoutParams = DefaultLayout, events: LReceiver = NoReceiver
  )(addValue: VDom[InlineItem]*): VDom[VField]
}

