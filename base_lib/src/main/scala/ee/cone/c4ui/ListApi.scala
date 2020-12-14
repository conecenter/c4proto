package ee.cone.c4ui

import ee.cone.c4di._
import ee.cone.c4actor.Context
import ee.cone.c4vdom.Types.VDom
import ee.cone.c4vdom._

trait CSSClassName extends Product {
  def name: String
}
case object NoCSSClassName extends CSSClassName { def name: String = "" }

trait VGridCell
@c4tagSwitch("UICompApp") trait DragHandle extends ToJson
@c4tagSwitch("UICompApp") trait GridCol extends ToJson {
  def colKey: String
}
@c4tagSwitch("UICompApp") trait GridColWidth extends ToJson
@c4tagSwitch("UICompApp") trait Expanding extends ToJson

trait VFilterItem
trait VFilterButton
trait VFilterItemContent extends OfDiv
@c4tagSwitch("UICompApp") trait FilterButtonArea extends ToJson

@c4tagSwitch("UICompApp") trait HighlightByAttr extends ToJson

trait VPivotCell
@c4tagSwitch("UICompApp") trait PivotSlice extends ToJson
@c4tagSwitch("UICompApp") trait PivotSliceWidth extends ToJson

@c4tags("UICompApp") trait ListTags[C] {
  @c4tag("GridRoot") def gridRoot(
    key: String,
    dragCol: Receiver[C],
    dragRow: Receiver[C],
    rowKeys: List[String],
    cols: List[GridCol],
    children: List[VDom[VGridCell]],
  ): VDom[OfDiv]
  @c4tag("GridCell") def gridCell(
    key: String,
    colKey: String,
    rowKey: String,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[OfDiv]] = Nil,
    expanding: Expanding = expandableExpanding,
    dragHandle: DragHandle = noDragHandle,
  ): VDom[VGridCell]
  @c4tag("") def expandableExpanding: Expanding
  @c4tag("none") def nonExpandableExpanding: Expanding
  @c4tag("expander") def expanderExpanding: Expanding
  @c4tag("") def noDragHandle: DragHandle
  @c4tag("x") def colDragHandle: DragHandle
  @c4tag("y") def rowDragHandle: DragHandle
  def gridCol(
    colKey: String,
    width: GridColWidth,
    hideWill: Int,
    isExpander: Boolean = false,
  ): GridCol
  @c4tag("bound") def boundGridColWidth(min: Int, max: Int): GridColWidth
  @c4tag("unbound") def unboundGridColWidth(min: Int): GridColWidth

  //
  @c4tag("FilterArea") def filterArea(
    key: String,
    centerButtonText: String,
    className: CSSClassName = NoCSSClassName,
    filters: List[VDom[VFilterItem]] = Nil,
    buttons: List[VDom[VFilterButton]] = Nil,
  ): VDom[OfDiv]
  @c4tag("FilterButtonPlace") def filterButtonPlace(
    key: String,
    area: FilterButtonArea,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[OfDiv]] = Nil,
  ): VDom[VFilterButton]
  @c4tag("FilterButtonExpander") def filterButtonExpander(
    key: String,
    area: FilterButtonArea,
    className: CSSClassName = NoCSSClassName,
    popupClassName: CSSClassName = NoCSSClassName,
    popupItemClassName: CSSClassName = NoCSSClassName,
    children: List[VDom[OfDiv]] = Nil,
    openedChildren: List[VDom[OfDiv]] = Nil,
    optButtons: List[VDom[VFilterButton]] = Nil,
  ): VDom[VFilterButton]
  @c4tag("lt") def leftFilterButtonArea: FilterButtonArea
  @c4tag("rt") def rightFilterButtonArea: FilterButtonArea
  @c4tag("FilterItem") def filterItem(
    key: String,
    minWidth: Int,
    maxWidth: Int,
    canHide: Boolean = false,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[VFilterItemContent]] = Nil,
  ): VDom[VFilterItem]
  //
  @c4tag("PopupManager") def popupManager(
    key: String,
    children: List[VDom[OfDiv]] = Nil,
  ): VDom[OfDiv]
  //
  @c4tag("Highlighter") def highlighter(
    key: String,
    attrName: HighlightByAttr
  ): VDom[OfDiv]
  @c4tag("data-row-key") def rowHighlightByAttr: HighlightByAttr
  @c4tag("data-col-key") def colHighlightByAttr: HighlightByAttr
  //
  @c4tag("PivotRoot") def pivotRoot(
    key: String,
    rows: List[PivotSlice],
    cols: List[PivotSlice],
    children: List[VDom[VPivotCell]],
  ): VDom[OfDiv]
  @c4tag("PivotCell") def pivotCell(
    key: String,
    colKey: String,
    rowKey: String,
    className: CSSClassName = NoCSSClassName,
    children: List[VDom[OfDiv]] = Nil,
  ): VDom[VPivotCell]
  @c4tag("group") def pivotSliceGroup(
    sliceKey: String,
    slices: List[PivotSlice],
  ): PivotSlice
  @c4tag("terminal") def terminalPivotSlice(
    sliceKey: String,
    width: PivotSliceWidth,
  ): PivotSlice
  @c4tag("bound") def boundPivotSliceWidth(min: Int, max: Int): PivotSliceWidth
  @c4tag("unbound") def unboundPivotSliceWidth(): PivotSliceWidth
}
