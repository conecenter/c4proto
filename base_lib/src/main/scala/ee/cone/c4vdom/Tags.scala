package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

trait OfDiv

trait Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv]
  def tag(key: VDomKey, tagName: TagName, attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def div(key: VDomKey, attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def divButton[State](key:VDomKey)(action:State=>State)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def seed(product: Product)(attr: List[TagStyle], src: String)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
}



trait SortHandler[State] extends Product {
  def handle(objKey: VDomKey, orderKeys: (VDomKey,VDomKey)): State => State
}
trait SortTags {
  def tBodyRoot[State](handler: SortHandler[State], items: List[ChildPair[OfDiv]]): ChildPair[OfDiv] //todo OfTable
  def handle(key: VDomKey, items: ChildPair[OfDiv]): ChildPair[OfDiv]
}