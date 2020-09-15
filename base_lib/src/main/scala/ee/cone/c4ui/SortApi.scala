package ee.cone.c4ui

import ee.cone.c4actor.Context
import ee.cone.c4vdom.{OfDiv, Receiver}
import ee.cone.c4vdom.Types.{VDom, VDomKey}

trait SortHandler extends Product {
  def handle(objKey: VDomKey, orderKeys: (VDomKey,VDomKey)): Context => Context
}
trait SortTags {
  def tBodyRoot(handler: SortHandler)(items: VDom[OfDiv]*): VDom[OfDiv] //todo OfTable
  def handle(key: VDomKey, item: VDom[OfDiv]): VDom[OfDiv]
  def toReceiver(value: Seq[VDomKey], handler: SortHandler): SortReceiver
}
trait SortReceiver extends Receiver[Context] {
  def value: List[VDomKey]
}
