package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

trait OfDiv

trait Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv]
  def tag(key: VDomKey, tagName: TagName, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def div(key: VDomKey, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def divButton[State](key:VDomKey)(action:Any⇒State)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def until(until: Long): ChildPair[OfDiv]
  def getUntil[C](pairs: List[ChildPair[_]]): (Long, List[ChildPair[_]])
  def seed(product: Product): ChildPair[OfDiv]
}
