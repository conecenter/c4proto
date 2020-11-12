package ee.cone.c4ui

import ee.cone.c4actor.Context
import ee.cone.c4di.{c4, provide}
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.VDomKey

@c4tags("UICompApp") trait InnerSortTags {
  @c4tag("TBodySortRoot") def tBodyRoot(key: String, sort: Receiver[Context])(children: VDom[OfDiv]*): VDom[OfDiv]
  @c4tag("SortHandle") def handle(key: String, item: VDom[OfDiv]): VDom[OfDiv]
}

@c4("UICompApp") final class SortTagsImpl(
  inner: InnerSortTags,
  sortReceiverFactory: SortReceiverFactory,
) extends SortTags {
  def tBodyRoot(handler: SortHandler)(items: VDom[OfDiv]*): VDom[OfDiv] =
    inner.tBodyRoot("body", sortReceiverFactory.create(handler))(items:_*)
  def handle(key: VDomKey, item: VDom[OfDiv]): VDom[OfDiv] = inner.handle(key,item)
}

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

////

@c4("UICompApp") final class SimpleJsonAdapterProvider(util: TagJsonUtils) {
  @provide def clientComponentType: Seq[JsonPairAdapter[ClientComponentType]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value)))
  @provide def string: Seq[JsonPairAdapter[String]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value)))
}