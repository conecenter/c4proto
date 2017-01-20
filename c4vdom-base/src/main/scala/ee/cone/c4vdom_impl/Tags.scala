package ee.cone.c4vdom_impl

import ee.cone.c4vdom._
import ee.cone.c4vdom.Types._

case class TextContentElement(content: String) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder) = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.append("content").append(content)
    builder.end()
  }
}

case class DivButton[State]()(val onClick:Option[Any⇒State]) extends VDomValue with OnClickReceiver[State] {
  def appendJson(builder: MutableJsonBuilder)={
    builder.startObject()
    builder.append("tp").append("div")
    onClick.foreach(_⇒ builder.append("onClick").append("send"))
    builder.append("style"); {
      builder.startObject()
      builder.append("cursor").append("pointer")
      builder.end()
    }
    builder.end()
  }
}

case object DivTagName extends TagName("div")
case class StyledValue(tagName: TagName, styles: List[TagStyle]) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder) = {
    builder.startObject()
    builder.append("tp").append(tagName.name)
    if(styles.nonEmpty){
      builder.append("style"); {
        builder.startObject()
        styles.foreach(_.appendStyle(builder))
        builder.end()
      }
    }
    builder.end()
  }
}

case class UntilElement(until: Long) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.end()
  }
}

case class SeedElement(seed: Product) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("Seed")
    builder.append("hash").append(seed.productElement(0).toString)
    builder.end()
  }
}


class TagsImpl(
  child: ChildPairFactory,
  utils: TagJsonUtils
) extends Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv] =
    child[OfDiv](key, TextContentElement(text), Nil)
  def tag(key: VDomKey, tagName: TagName, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key, StyledValue(tagName, attr.toList), children)
  def div(key: VDomKey, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    tag(key, DivTagName, attr:_*)(children)
  def divButton[State](key:VDomKey)(action:Any⇒State)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key,DivButton()(Some(action)), children)
  def until(key: VDomKey, until: Long): ChildPair[OfDiv] =
    child[OfDiv](key,UntilElement(until), Nil)
  def seed(product: Product): ChildPair[OfDiv] = ???
}

