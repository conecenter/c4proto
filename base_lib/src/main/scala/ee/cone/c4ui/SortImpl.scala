package ee.cone.c4ui

import ee.cone.c4actor.Context
import ee.cone.c4di.{c4, provide}
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.VDomKey

//@c4tags("UICompApp") trait InnerSortTags[C] {
//  @c4tag("TBodySortRoot") def tBodyRoot(key: String, sort: Receiver[C], children: List[VDom[OfDiv]]): VDom[OfDiv]
//  @c4tag("SortHandle") def handle(key: String, item: VDom[OfDiv]): VDom[OfDiv]
//}

//@c4("UICompApp") final class SortTagsImpl(
//  innerProvider: InnerSortTagsProvider,
//  sortReceiverFactory: SortReceiverFactory,
//)(
//  inner: InnerSortTags[Context] = innerProvider.get[Context]
//) extends SortTags {
//  def tBodyRoot(handler: SortHandler)(items: ChildPair[OfDiv]*): ChildPair[OfDiv] =
//    inner.tBodyRoot("body", sortReceiverFactory.create(handler), items.toList)
//  def handle(key: VDomKey, item: ChildPair[OfDiv]): ChildPair[OfDiv] = inner.handle(key,item)
//}

@c4("UICompApp") final class SortReceiverFactoryImpl extends SortReceiverFactory {
  def create(handler: SortHandler): Receiver[Context] =
    SortReceiverImpl(handler)
}

case class SortReceiverImpl(handler: SortHandler) extends Receiver[Context] {
  def receive: Handler = message =>
    handler.handle(
      message.header("x-r-sort-obj-key"),
      (message.header("x-r-sort-order-0"), message.header("x-r-sort-order-1")),
    )
}
