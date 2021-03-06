package ee.cone.c4ui

import ee.cone.c4actor.Context
import ee.cone.c4vdom.Receiver
import ee.cone.c4vdom.Types.VDomKey

trait SortHandler extends Product {
  def handle(objKey: VDomKey, orderKeys: (VDomKey,VDomKey)): Context => Context
}
//trait SortTags {
//  def tBodyRoot(handler: SortHandler)(items: VDom[OfDiv]*): VDom[OfDiv] //todo OfTable
//  def handle(key: VDomKey, item: VDom[OfDiv]): VDom[OfDiv]
//}

trait SortReceiverFactory {
  def create(handler: SortHandler): Receiver[Context]
}

//trait SortReceiver extends Receiver[Context] {
//  def value: List[VDomKey]
//}
