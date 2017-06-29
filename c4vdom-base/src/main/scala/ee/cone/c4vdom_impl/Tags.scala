package ee.cone.c4vdom_impl

import ee.cone.c4vdom._
import ee.cone.c4vdom.Types._




case class TextContentElement(content: String) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.append("content").append(content)
    builder.end()
  }
}

case class DivButton[State]()(val receive:VDomMessage ⇒ State ⇒ State) extends VDomValue with Receiver[State] {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("div")
    builder.append("onClick").append("sendThen")
    builder.append("style"); {
      builder.startObject()
      builder.append("cursor").append("pointer")
      builder.end()
    }
    builder.end()
  }
}

case object DivTagName extends TagName("div")
case class StyledValue(tagName: TagName, styles: List[TagStyle])(utils: TagJsonUtils) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append(tagName.name)
    utils.appendStyles(builder, styles)
    builder.end()
  }
}

case class SeedElement(seed: Product, styles: List[TagStyle])(utils: TagJsonUtils) extends SeedVDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("div")
    utils.appendStyles(builder, styles)
    builder.append("ref");{
      builder.startArray()
      builder.append("seed")
      builder.append(seed.productElement(0).toString)
      builder.end()
    }
    builder.end()
  }
}

class TagsImpl(
  child: ChildPairFactory,
  utils: TagJsonUtils
) extends Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv] =
    child[OfDiv](key, TextContentElement(text), Nil)
  def tag(key: VDomKey, tagName: TagName, attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key, StyledValue(tagName, attr)(utils), children)
  def div(key: VDomKey, attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    tag(key, DivTagName, attr)(children)
  def divButton[State](key:VDomKey)(action:State⇒State)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key,DivButton()((_:VDomMessage)⇒action), children)
  def seed(product: Product)(attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](product.productElement(0).toString,SeedElement(product,attr)(utils), children)
}

