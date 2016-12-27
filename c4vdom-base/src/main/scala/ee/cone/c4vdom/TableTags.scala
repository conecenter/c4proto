package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

trait OfTable
trait OfTableRow

trait TableTags {
  type CellContentVariant = Boolean
  def table(key: VDomKey, attr: TagAttr*)(children:List[ChildPair[OfTable]]): List[ChildPair[OfDiv]]
  def row(key: VDomKey, attr: TagAttr*)(children:List[ChildPair[OfTableRow]]): ChildPair[OfTable]
  def group(key:VDomKey, attr: TagAttr*)(children:List[ChildPair[OfDiv]]): ChildPair[OfTableRow]
  def cell(key:VDomKey, attr: TagAttr*)(children:CellContentVariant=>List[ChildPair[OfDiv]]): ChildPair[OfTableRow]
}

case object IsHeader extends TagAttr
case class MaxWidth(value:Int) extends TagAttr
case class MinWidth(value:Int) extends TagAttr
