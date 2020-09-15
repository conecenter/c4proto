package ee.cone.c4ui

import ee.cone.c4actor.Context
import ee.cone.c4di.{c4, provide}
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.VDomKey

@c4tags("UICompApp") trait InnerSortTags {
  @c4tag("TBodySortRoot") def tBodyRoot(key: String, value: SortReceiver)(children: VDom[OfDiv]*): VDom[OfDiv]
  @c4tag("SortHandle") def handle(key: String, item: VDom[OfDiv]): VDom[OfDiv]
}

@c4("UICompApp") final class SortTagsImpl(
  inner: InnerSortTags
) extends SortTags {
  def tBodyRoot(handler: SortHandler)(items: VDom[OfDiv]*): VDom[OfDiv] =
    inner.tBodyRoot("body", toReceiver(items.map(_.key),handler))(items:_*)
  def handle(key: VDomKey, item: VDom[OfDiv]): VDom[OfDiv] = inner.handle(key,item)
  def toReceiver(value: Seq[VDomKey], handler: SortHandler): SortReceiver =
    SortReceiverImpl(value.toList, handler)
}

case class SortReceiverImpl(value: List[VDomKey], handler: SortHandler) extends SortReceiver {
  def receive: Handler = message =>
    handler.handle(
      message.header("x-r-sort-obj-key"),
      (message.header("x-r-sort-order-0"), message.header("x-r-sort-order-1")),
    )
}

@c4("UICompApp") final class SortJsonAdapterProvider(util: TagJsonUtils) {
  @provide def sortReceiver: Seq[JsonPairAdapter[SortReceiver]] =
    List(util.jsonPairAdapter((value,builder) => {
      builder.startArray()
      value.value.foreach(builder.just.append)
      builder.end()
    }))
}

////

@c4("UICompApp") final class SimpleJsonAdapterProvider(util: TagJsonUtils) {
  @provide def clientComponentType: Seq[JsonPairAdapter[ClientComponentType]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value)))
  @provide def string: Seq[JsonPairAdapter[String]] =
    List(util.jsonPairAdapter((value, builder) => builder.just.append(value)))
}