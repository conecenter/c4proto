package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

trait OfDiv

trait Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv]
  def tag(key: VDomKey, tagName: TagName, attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def div(key: VDomKey, attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def divButton[State](key:VDomKey)(action:State⇒State)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def seed(product: Product)(attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
}
