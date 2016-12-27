package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

trait OfDiv

trait Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv]
  def tag(key: VDomKey, tagName: TagName, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def div(key: VDomKey, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
  def divButton(key:VDomKey)(action:()=>Unit)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv]
}
