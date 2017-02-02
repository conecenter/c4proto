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

case class DivButton[State]()(val onClick:Option[String⇒State⇒State]) extends VDomValue with OnClickReceiver[State] {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("div")
    onClick.foreach(_⇒ builder.append("onClick").append("sendThen"))
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
  def appendJson(builder: MutableJsonBuilder): Unit = {
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

case class SeedElement(seed: Product) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("div")
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
    child[OfDiv](key, StyledValue(tagName, attr), children)
  def div(key: VDomKey, attr: List[TagStyle])(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    tag(key, DivTagName, attr)(children)
  def divButton[State](key:VDomKey)(action:State⇒State)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key,DivButton()(Some((_:String)⇒action)), children)
  def seed(product: Product): ChildPair[OfDiv] =
    child[OfDiv](product.productElement(0).toString,SeedElement(product), Nil)
}

