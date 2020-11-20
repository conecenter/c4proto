package ee.cone.c4ui

import java.text.{DecimalFormat, NumberFormat}

import ee.cone.c4di._
import ee.cone.c4actor.Context
import ee.cone.c4ui.ListTagTypes._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._


trait VGridCell
trait VGridCellContent extends OfDiv

object ListTagTypes {
  type VGridRoot = OfDiv
  type VFilterArea = OfDiv
}

trait VFilterItem
trait VFilterButton
trait VFilterButtonOption
trait VFilterItemContent extends OfDiv

abstract class CSSClassName(val value: String) extends Product
case object NoCSSClassName extends CSSClassName("")

sealed abstract class FilterButtonArea(val value: String) extends Product
case object LeftFilterButtonArea extends FilterButtonArea("lt")
case object RightFilterButtonArea extends FilterButtonArea("rt")

sealed abstract class DragHandle(val value: String) extends Product
case object NoDragHandle extends DragHandle("")
case object ColDragHandle extends DragHandle("x")
case object RowDragHandle extends DragHandle("y")

@c4("UICompApp") final class ListJsonAdapterProvider(util: TagJsonUtils)(
  val intAdapter: JsonPairAdapter[Int] = util.jsonPairAdapter((value,builder) => {
    val format = NumberFormat.getIntegerInstance match { case f: DecimalFormat => f } // to do once?
    builder.just.append(BigDecimal(value),format)
  })
) {
  @provide def forCSSClassName: Seq[JsonPairAdapter[CSSClassName]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value.value)))
  @provide def forFilterButtonArea: Seq[JsonPairAdapter[FilterButtonArea]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value.value)))
  @provide def forDragHandle: Seq[JsonPairAdapter[DragHandle]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value.value)))
  @provide def forReceiver: Seq[JsonPairAdapter[Receiver[Context]]] = List(new JsonPairAdapter[Receiver[Context]]{
    def appendJson(key: String, value: Receiver[Context], builder: MutableJsonBuilder): Unit = {}
  })
  @provide def forStringList: Seq[JsonPairAdapter[List[String]]] =
    List(util.jsonPairAdapter(forList((value, builder) => {
      builder.just.append(value)
    })))
  @provide def forInt: Seq[JsonPairAdapter[Int]] =   List(intAdapter)
  @provide def forBoolean: Seq[JsonPairAdapter[Boolean]] =
    List(util.jsonPairAdapter((value,builder) => builder.just.append(value)))
  @provide def forGridColList: Seq[JsonPairAdapter[List[GridCol]]] =
    List(util.jsonPairAdapter(forList((value, builder) => {
      builder.startObject()
      builder.append("colKey").append(value.colKey)
      intAdapter.appendJson("maxWidth",value.maxWidth,builder)
      intAdapter.appendJson("minWidth",value.minWidth,builder)
      intAdapter.appendJson("hideWill",value.hideWill,builder)
      if(value.isExpander) builder.append("isExpander").append(true)
      builder.end()
    })))
  def forList[T](forItem: (T,MutableJsonBuilder)=>Unit): (List[T],MutableJsonBuilder)=>Unit = (list,builder) => {
    builder.startArray()
    list.foreach(forItem(_,builder))
    builder.end()
  }
}

case class GridCol(
  colKey: String,
  minWidth: Int,
  maxWidth: Int,
  hideWill: Int,
  isExpander: Boolean = false,
)

@c4tags("UICompApp") trait ListTags {
  @c4tag("GridRoot") def gridRoot(
    key: String,
    dragCol: Receiver[Context],
    dragRow: Receiver[Context],
    rowKeys: List[String],
    cols: List[GridCol],
    children: List[VDom[VGridCell]],
  ): VDom[VGridRoot]
  @c4tag("GridCell") def gridCell(
    key: String,
    colKey: String,
    rowKey: String,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[VGridCellContent]] = Nil,
    isExpander: Boolean = false,
    dragHandle: DragHandle = NoDragHandle,
  ): VDom[VGridCell]

  @c4tag("FilterArea") def filterArea(
    key: String,
    centerButtonText: String,
    filters: List[VDom[VFilterItem]] = Nil,
    buttons: List[VDom[VFilterButton]] = Nil,
  ): VDom[VFilterArea]
  @c4tag("FilterButton") def filterButton(
    key: String,
    minWidth: Int,
    area: FilterButtonArea,
    children: List[VDom[OfDiv]] = Nil,
  ): VDom[VFilterButton]
  @c4tag("FilterButtonExpander") def filterButtonExpander(
    key: String,
    minWidth: Int,
    area: FilterButtonArea,
    optButtons: List[VDom[VFilterButtonOption]],
    className: CSSClassName = NoCSSClassName,
  ): VDom[VFilterButton]
  @c4tag("FilterButtonOption") def filterButtonOption(
    key: String,
    minWidth: Int,
    activate: Receiver[Context],
    className: CSSClassName = NoCSSClassName,
    caption: String = "",
  ): VDom[VFilterButtonOption]
  @c4tag("FilterItem") def filterItem(
    key: String,
    minWidth: Int,
    maxWidth: Int,
    canHide: Boolean = false,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[VFilterItemContent]] = Nil,
  ): VDom[VFilterItem]

}