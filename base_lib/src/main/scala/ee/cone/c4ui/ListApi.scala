package ee.cone.c4ui

import ee.cone.c4di._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._

trait CSSClassName extends Product {
  def name: String
}
case object NoCSSClassName extends CSSClassName { def name: String = "" }

trait Cell extends ToChildPair {
  def colKey: String
  def rowKey: String
  def key: String = s"cell-$colKey-$rowKey"
}

trait GridCell extends Cell
@c4tagSwitch("UICompApp") trait DragHandle extends ToJson
@c4tagSwitch("UICompApp") trait GridRow extends ToJson {
  def rowKey: String
}
@c4tagSwitch("UICompApp") trait GridCol extends ToJson {
  def colKey: String
}
@c4tagSwitch("UICompApp") trait GridColWidth extends ToJson
@c4tagSwitch("UICompApp") trait Expanding extends ToJson

trait FilterItem extends ToChildPair
trait FilterButton extends ToChildPair
@c4tagSwitch("UICompApp") trait FilterButtonArea extends ToJson

@c4tagSwitch("UICompApp") trait HighlightByAttr extends ToJson

trait PivotCell extends Cell
@c4tagSwitch("UICompApp") trait PivotSlice extends ToJson {
  def sliceKey: String
  def slices: List[PivotSlice]
}
trait PivotTerminalSlice extends PivotSlice {
  def slices: List[PivotSlice] = Nil
}
trait PivotGroupSlice extends PivotSlice {
  def slices: List[PivotSlice]
}
@c4tagSwitch("UICompApp") trait PivotSliceWidth extends ToJson

@c4tags("UICompApp") trait ListTags[C] {
  @c4el("GridRoot") def gridRoot(
    key: String,
    dragCol: Receiver[C],
    dragRow: Receiver[C],
    rows: List[GridRow],
    cols: List[GridCol],
    children: ElList[GridCell],
  ): ToChildPair
  @c4val def gridRow(
    rowKey: String
  ): GridRow
  @c4val def gridCol(
    colKey: String,
    width: GridColWidth,
    hideWill: Int,
    isExpander: Boolean = false,
  ): GridCol
  @c4el("GridCell") def gridCell(
    rowKey: String,
    colKey: String,
    children: ChildPairList[OfDiv] = Nil,
    classNames: List[CSSClassName] = Nil,
    expanding: Expanding = expandableExpanding,
    dragHandle: DragHandle = noDragHandle,
  ): GridCell
  @c4val("") def expandableExpanding: Expanding
  @c4val("none") def nonExpandableExpanding: Expanding
  @c4val("expander") def expanderExpanding: Expanding
  @c4val("") def noDragHandle: DragHandle
  @c4val("x") def colDragHandle: DragHandle
  @c4val("y") def rowDragHandle: DragHandle
  @c4val("bound") def boundGridColWidth(min: Int, max: Int): GridColWidth
  @c4val("unbound") def unboundGridColWidth(min: Int): GridColWidth

  //
  @c4el("FilterArea") def filterArea(
    key: String,
    className: CSSClassName = NoCSSClassName,
    filters: ElList[FilterItem] = Nil,
    buttons: ElList[FilterButton] = Nil,
  ): ToChildPair
  @c4el("FilterButtonPlace") def filterButtonPlace(
    key: String,
    area: FilterButtonArea,
    className: CSSClassName = NoCSSClassName,
    children: ChildPairList[OfDiv] = Nil,
  ): FilterButton
  @c4el("FilterButtonExpander") def filterButtonExpander(
    key: String,
    area: FilterButtonArea,
    className: CSSClassName = NoCSSClassName,
    popupClassName: CSSClassName = NoCSSClassName,
    popupItemClassName: CSSClassName = NoCSSClassName,
    children: ChildPairList[OfDiv] = Nil,
    openedChildren: ChildPairList[OfDiv] = Nil,
    optButtons: ElList[FilterButton] = Nil,
  ): FilterButton
  @c4val("lt") def leftFilterButtonArea: FilterButtonArea
  @c4val("rt") def rightFilterButtonArea: FilterButtonArea
  @c4el("FilterItem") def filterItem(
    key: String,
    minWidth: Int,
    maxWidth: Int,
    canHide: Boolean = false,
    className: CSSClassName = NoCSSClassName,
    children: ChildPairList[OfDiv] = Nil,
  ): FilterItem
  //
  @c4el("PopupManager") def popupManager(
    key: String,
    children: ChildPairList[OfDiv] = Nil,
  ): ToChildPair
  //
  @c4el("Highlighter") def highlighter(
    key: String,
    attrName: HighlightByAttr,
    highlightClass: CSSClassName = NoCSSClassName,
    notHighlightClass: CSSClassName = NoCSSClassName,
  ): ToChildPair
  @c4val("data-row-key") def rowHighlightByAttr: HighlightByAttr
  @c4val("data-col-key") def colHighlightByAttr: HighlightByAttr
  //
  @c4el("PivotRoot") def pivotRoot(
    key: String,
    rows: List[PivotSlice],
    cols: List[PivotSlice],
    children: ElList[PivotCell],
    classNames: List[CSSClassName] = Nil,
  ): ToChildPair
  @c4el("PivotCell") def pivotCell(
    colKey: String,
    rowKey: String,
    classNames: List[CSSClassName] = Nil,
    children: ChildPairList[OfDiv] = Nil,
  ): PivotCell
  @c4val("group") def pivotGroupSlice(
    sliceKey: String,
    slices: List[PivotSlice],
  ): PivotGroupSlice
  @c4val("terminal") def pivotTerminalSlice(
    sliceKey: String,
    width: PivotSliceWidth,
  ): PivotTerminalSlice
  @c4val("bound") def boundPivotSliceWidth(min: Int, max: Int): PivotSliceWidth
  @c4val("unbound") def unboundPivotSliceWidth(): PivotSliceWidth
}
